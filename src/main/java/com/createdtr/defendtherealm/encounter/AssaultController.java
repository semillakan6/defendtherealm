package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.integration.cbc.PrototypeWeapon;
import com.createdtr.defendtherealm.integration.sable.SableVehicles;
import com.createdtr.defendtherealm.navigation.RoutePlanners;
import com.createdtr.defendtherealm.navigation.VehicleRoutePlanner;
import com.createdtr.defendtherealm.navigation.VehicleRoutePlanner.Envelope;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.joml.Vector3d;

@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class AssaultController {
    public static final int POST_IMPACT_LINGER_TICKS = 60;
    private record Drive(UUID encounter, ServerSubLevel ship, Vec3 destination, BlockPos hq) {}
    private record RouteKey(UUID assault, String dimension, String profile, Envelope envelope, BlockPos hq, BlockPos start) {}
    private static final Map<ServerLevel, Drive> DRIVES = new HashMap<>();
    private static final Map<RouteKey, List<BlockPos>> CACHE = new HashMap<>();
    private static final java.util.Set<UUID> PHYSICS_SEEN = new java.util.HashSet<>();
    private AssaultController() {}

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { DRIVES.clear(); CACHE.clear(); PHYSICS_SEEN.clear(); }

    public static BlockPos position(CompoundTag tag) { return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")); }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var data = EncounterSavedData.get(event.getServer());
        var encounter = data.encounter();
        if (encounter == null || encounter.state() == Encounter.State.COMPLETED) { DRIVES.clear(); CACHE.clear(); return; }
        CompoundTag context = data.context();
        if (!context.getBoolean("hqAssault")) return; // Legacy coordinate-only records remain operator controlled.
        ServerLevel level = null;
        for (var candidate : event.getServer().getAllLevels())
            if (candidate.dimension().location().toString().equals(context.getString("dimension"))) level = candidate;
        if (level == null) { encounter.terminate(Encounter.Reason.RECOVERY_FAILED); data.setDirty(); return; }
        try {
            if (encounter.state() == Encounter.State.DESTROYING) encounter.advance(Encounter.State.CLEANING_UP);
            if (encounter.state() == Encounter.State.CLEANING_UP) {
                var drive = DRIVES.remove(level);
                if (drive != null) PrototypeWeapon.stop(PrototypeWeapon.find(drive.ship()));
                SableVehicles.removeOwned(level, encounter);
                if (encounter.owned().isEmpty()) encounter.advance(Encounter.State.COMPLETED);
                CACHE.keySet().removeIf(k -> k.assault().equals(encounter.id()));
                data.setDirty(); return;
            }
            if (encounter.state() == Encounter.State.SPAWNING) return;
            long now = level.getGameTime();
            BlockPos target = position(context.getCompound("target"));
            boolean targetPresent = level.hasChunkAt(target)
                    && level.getBlockState(target).is(CreateDefendtheRealm.DEV_HQ.get());
            boolean destructionConfirmed = context.getBoolean("targetDestroyedByProjectile");
            if (!targetPresent && !destructionConfirmed) {
                encounter.terminate(Encounter.Reason.LOST_TARGET); DRIVES.remove(level); data.setDirty(); return;
            }
            var container = SubLevelContainer.getContainer(level);
            var candidate = container == null || !context.hasUUID("rootVehicle") ? null : container.getSubLevel(context.getUUID("rootVehicle"));
            if (!(candidate instanceof ServerSubLevel ship) || ship.isRemoved()) {
                DRIVES.remove(level);
                int missing = context.getInt("missingTicks") + 1; context.putInt("missingTicks", missing);
                if (missing > 200) encounter.terminate(Encounter.Reason.RECOVERY_FAILED);
                data.updateContext(context); return;
            }
            context.putInt("missingTicks", 0);
            PrototypeWeapon.stop(PrototypeWeapon.find(ship));
            Vec3 current = new Vec3(ship.logicalPose().position().x(), ship.logicalPose().position().y(), ship.logicalPose().position().z());
            if (destructionConfirmed && !targetPresent) {
                int remaining = postImpactTicksRemaining(now, context.getLong("targetDestroyedTick"));
                context.putInt("postImpactTicksRemaining", remaining);
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target));
                if (remaining == 0) {
                    encounter.terminate(Encounter.Reason.TARGET_DESTROYED);
                    DRIVES.remove(level);
                }
                data.updateContext(context);
                return;
            }
            if (destructionConfirmed) {
                context.remove("targetDestroyedByProjectile");
                context.remove("targetDestroyedTick");
                context.remove("targetDestroyingProjectile");
                context.remove("postImpactTicksRemaining");
            }
            if (!context.contains("approachStarted")) context.putLong("approachStarted", now);
            if (now - context.getLong("approachStarted") > 7200
                    || encounter.state() == Encounter.State.APPROACHING && now - context.getLong("approachStarted") > 3600) {
                encounter.terminate(Encounter.Reason.TIMEOUT); DRIVES.remove(level); data.updateContext(context); return;
            }
            Envelope envelope = envelope(ship, context);
            List<BlockPos> route = java.util.Arrays.stream(context.getLongArray("route")).mapToObj(BlockPos::of).toList();
            int cursor = context.getInt("routeCursor");
            if (route.isEmpty()) {
                route = initialRoute(level, encounter.id(), current, target, envelope);
                if (route.isEmpty()) { encounter.terminate(Encounter.Reason.OBSTRUCTED); data.updateContext(context); return; }
                cursor = Math.min(1, route.size() - 1);
                context.putLong("lastProgress", now); context.putDouble("progressDistance", Double.MAX_VALUE);
            }
            if (cursor < 0 || cursor >= route.size()) throw new IllegalArgumentException("Invalid saved route cursor");
            while (cursor < route.size() - 1 && (current.distanceTo(Vec3.atCenterOf(route.get(cursor))) < 3.0
                    || cursor + 1 < route.size() && VehicleRoutePlanner.clearSegment(level, current,
                            Vec3.atCenterOf(route.get(cursor + 1)), envelope)
                            && current.distanceTo(Vec3.atCenterOf(route.get(cursor + 1)))
                            < current.distanceTo(Vec3.atCenterOf(route.get(cursor))))) {
                cursor++; context.putLong("lastProgress", now); context.putDouble("progressDistance", Double.MAX_VALUE);
            }
            // Follow the cached corridor with a bounded lookahead instead of
            // fighting every block-sized A* node with a large rigid body.
            for (int i = route.size() - 1; i > cursor; i--) {
                Vec3 lookahead = Vec3.atCenterOf(route.get(i));
                if (current.distanceTo(lookahead) <= 32
                        && VehicleRoutePlanner.clearSegment(level, current, lookahead, envelope)) {
                    cursor = i;
                    context.putLong("lastProgress", now);
                    context.putDouble("progressDistance", Double.MAX_VALUE);
                    break;
                }
            }
            Vec3 next = Vec3.atCenterOf(route.get(cursor));
            double distance = current.distanceTo(next);
            if (context.getDouble("progressDistance") - distance >= 1) {
                context.putDouble("progressDistance", distance); context.putLong("lastProgress", now);
            }
            boolean obstructed = now % 10 == 0 && !VehicleRoutePlanner.clearSegment(level, current, next, envelope);
            boolean stuck = encounter.state() == Encounter.State.APPROACHING && distance > 1 && now - context.getLong("lastProgress") >= 100;
            if (obstructed || stuck) {
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target));
                if (now < context.getLong("nextRepair")) { data.updateContext(context); return; }
                context.putLong("nextRepair", now + 40);
                var repaired = repair(level, current, route, cursor, envelope);
                context.putInt("repairs", context.getInt("repairs") + 1);
                if (repaired.isEmpty()) {
                    context.putInt("failedRepairs", context.getInt("failedRepairs") + 1);
                    if (context.getInt("failedRepairs") >= 3) encounter.terminate(Encounter.Reason.OBSTRUCTED);
                    data.updateContext(context); return;
                }
                route = repaired; cursor = Math.min(1, route.size() - 1); next = Vec3.atCenterOf(route.get(cursor));
                context.putLong("lastProgress", now); context.putDouble("progressDistance", Double.MAX_VALUE);
            }
            context.putLongArray("route", route.stream().mapToLong(BlockPos::asLong).toArray());
            context.putInt("routeCursor", cursor);
            context.putDouble("waypointDistance", current.distanceTo(next));
            context.putDouble("vehicleX", current.x);
            context.putDouble("vehicleY", current.y);
            context.putDouble("vehicleZ", current.z);
            context.putDouble("speed", ship.latestLinearVelocity.length());
            if (cursor == route.size() - 1 && current.distanceTo(next) < 1 && encounter.state() == Encounter.State.APPROACHING)
                encounter.advance(Encounter.State.ENGAGING);
            DRIVES.put(level, new Drive(encounter.id(), ship, next, target));
            PrototypeWeapon.tick(ship, target, context, encounter.combatEnabled() && current.distanceTo(next) < 2);
            data.updateContext(context);
        } catch (RuntimeException ex) {
            DRIVES.remove(level);
            context.putString("controllerFailure", ex.toString());
            encounter.terminate(Encounter.Reason.WEAPON_FAILURE);
            data.updateContext(context);
            CreateDefendtheRealm.LOGGER.error("HQ assault {} stopped", encounter.id(), ex);
        }
    }

    public static int postImpactTicksRemaining(long now, long destroyedTick) {
        long elapsed = now >= destroyedTick ? now - destroyedTick : 0;
        return (int) Math.max(0, POST_IMPACT_LINGER_TICKS - elapsed);
    }

    private static Envelope envelope(ServerSubLevel ship, CompoundTag context) {
        if (!context.contains("navRadius")) {
            var b = ship.boundingBox(); var p = ship.logicalPose().position();
            double x = Math.max(Math.abs(b.minX() - p.x()), Math.abs(b.maxX() - p.x()));
            double z = Math.max(Math.abs(b.minZ() - p.z()), Math.abs(b.maxZ() - p.z()));
            context.putDouble("navRadius", Math.hypot(x, z) + 1);
            context.putDouble("navBelow", Math.max(0, p.y() - b.minY()) + 1);
            context.putDouble("navAbove", Math.max(0, b.maxY() - p.y()) + 1);
        }
        return new Envelope(context.getDouble("navRadius"), context.getDouble("navBelow"), context.getDouble("navAbove"));
    }

    public static List<BlockPos> initialRoute(ServerLevel level, UUID assault, Vec3 start, BlockPos target, Envelope envelope) {
        var key = new RouteKey(assault, level.dimension().location().toString(), "hover_airship", envelope, target,
                new BlockPos(((int) Math.floor(start.x)) >> 4, ((int) Math.floor(start.y)) >> 4, ((int) Math.floor(start.z)) >> 4));
        List<BlockPos> cached = CACHE.get(key);
        if (cached != null && VehicleRoutePlanner.clearSegment(level, start, Vec3.atCenterOf(cached.getFirst()), envelope)) return cached;
        double angle = Math.atan2(start.z - target.getZ(), start.x - target.getX());
        for (int i = 0; i < 8; i++) {
            double a = angle + i * Math.PI / 4;
            BlockPos goal = BlockPos.containing(target.getX() + Math.cos(a) * 28,
                    start.y, target.getZ() + Math.sin(a) * 28);
            List<BlockPos> route = RoutePlanners.get("hover_airship").plan(level, BlockPos.containing(start), goal, envelope);
            if (!route.isEmpty()) { CACHE.put(key, route); return route; }
        }
        return List.of();
    }
    private static List<BlockPos> repair(ServerLevel level, Vec3 current, List<BlockPos> route, int cursor, Envelope envelope) {
        int attempts = 0;
        for (int i = route.size() - 1; i >= cursor && attempts < 3; i--) {
            if (current.distanceTo(Vec3.atCenterOf(route.get(i))) > 32) continue;
            if (!VehicleRoutePlanner.clear(level, Vec3.atCenterOf(route.get(i)), envelope)) continue;
            attempts++;
            var connector = RoutePlanners.get("hover_airship").plan(level, BlockPos.containing(current), route.get(i), envelope);
            if (connector.isEmpty()) continue;
            List<BlockPos> repaired = new ArrayList<>(connector);
            repaired.addAll(route.subList(i + 1, route.size()));
            return List.copyOf(repaired);
        }
        return List.of();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void physics(ForgeSablePrePhysicsTickEvent event) {
        ServerLevel level = event.getPhysicsSystem().getLevel();
        Drive drive = DRIVES.get(level);
        if (drive == null || drive.ship().isRemoved()) return;
        var encounter = EncounterSavedData.get(level.getServer()).encounter();
        if (encounter == null || !encounter.id().equals(drive.encounter())
                || encounter.state() != Encounter.State.APPROACHING && encounter.state() != Encounter.State.ENGAGING) return;
        if (PHYSICS_SEEN.add(encounter.id())) CreateDefendtheRealm.LOGGER.info("Assisted physics active for {} timestep={}", encounter.id(), event.getTimeStep());
        var ship = drive.ship(); var handle = event.getPhysicsSystem().getPhysicsHandle(ship);
        if (handle == null || !handle.isValid()) return;
        Vector3d desired = new Vector3d(drive.destination().x, drive.destination().y, drive.destination().z)
                .sub(ship.logicalPose().position());
        if (desired.length() > 1.25) desired.normalize(1.25);
        Vector3d velocity = new Vector3d(handle.getLinearVelocity());
        Vector3d velocityCorrection = desired.sub(velocity);
        Vector3d forward = ship.logicalPose().transformNormal(new Vector3d(0, 0, -1));
        Vector3d up = ship.logicalPose().transformNormal(new Vector3d(0, 1, 0));
        Vector3d to = new Vector3d(drive.hq().getX() + 0.5, 0, drive.hq().getZ() + 0.5)
                .sub(ship.logicalPose().position().x(), 0, ship.logicalPose().position().z());
        double yaw = Math.atan2(forward.z * to.x - forward.x * to.z, forward.x * to.x + forward.z * to.z);
        Vector3d upright = up.cross(new Vector3d(0, 1, 0), new Vector3d()).mul(4);
        Vector3d targetAngular = upright.add(0, Math.clamp(yaw * 1.5, -0.5, 0.5), 0);
        if (targetAngular.length() > 0.5) targetAngular.normalize(0.5);
        Vector3d angularVelocity = new Vector3d(handle.getAngularVelocity());
        Vector3d angular = targetAngular.sub(angularVelocity);
        // Sable owns the body's effective mass and inertia. Apply exact velocity
        // deltas here instead of estimating impulses from the add-on mass tracker;
        // the latter can feed energy into Aeronautics vehicles with active lift.
        handle.addLinearAndAngularVelocity(velocityCorrection, angular);
    }
}
