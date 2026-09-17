package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.integration.cbc.PrototypeWeapon;
import com.createdtr.defendtherealm.combat.VehicleFamily;
import com.createdtr.defendtherealm.combat.VehicleCombatProfiles;
import com.createdtr.defendtherealm.combat.WeaponRuntime;
import com.createdtr.defendtherealm.combat.WeaponRangeProfile;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class AssaultController {
    public static final int POST_IMPACT_LINGER_TICKS = 60;
    static final double WAYPOINT_REACHED_DISTANCE = 3.0;
    static final double MINIMUM_MANEUVER_DISTANCE = 3.0;
    static final int MANEUVER_STUCK_TICKS = 100;
    private static final int MANEUVER_SEARCH_DEADLINE_TICKS = 600;
    private static final int ORBIT_SEARCHES_PER_TICK = 4;
    private static final double ROUTE_LOOKAHEAD_DISTANCE = 32.0;
    private static final double HOLD_DEADBAND = 0.35;
    private record Drive(UUID encounter, ServerSubLevel ship, Vec3 destination, BlockPos hq, boolean descending) {}
    private record RouteKey(UUID assault, String dimension, VehicleFamily family, String profile, Envelope envelope,
            BlockPos hq, BlockPos start, double engagementRadius) {}
    private record TacticalResult(List<BlockPos> route, int cursor, Vec3 destination) {}
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
        if (level == null) {
            context.putString("controllerFailure", "Encounter dimension is temporarily unavailable");
            context.putBoolean("controllerSuspended", true);
            data.updateContext(context);
            return;
        }
        try {
            if (encounter.state() == Encounter.State.DESTROYING) {
                tickDestroying(level, data, encounter, context);
                return;
            }
            if (encounter.state() == Encounter.State.CLEANING_UP) {
                var drive = DRIVES.remove(level);
                if (drive != null) PrototypeWeapon.stopAll(drive.ship());
                com.createdtr.defendtherealm.integration.cbc.ShotAccounting.cleanupProjectiles(level, data);
                EncounterChunkTickets.release(level, data);
                var cleanup = SableVehicles.removeOwned(level, data);
                CompoundTag cleanupContext = data.context();
                if (cleanup.pending() > 0) {
                    cleanupContext.putInt("cleanupMissingTicks", cleanupContext.getInt("cleanupMissingTicks") + 1);
                    if (cleanupContext.getBoolean("sableReconciled") && cleanupContext.getInt("cleanupMissingTicks") > 200)
                        cleanupContext.putInt("acknowledgedAbsentObjects",
                                cleanupContext.getInt("acknowledgedAbsentObjects")
                                        + SableVehicles.acknowledgeAuthoritativelyAbsent(level, encounter));
                }
                data.updateContext(cleanupContext);
                if (encounter.owned().isEmpty()) encounter.advance(Encounter.State.COMPLETED);
                CACHE.keySet().removeIf(k -> k.assault().equals(encounter.id()));
                data.setDirty(); return;
            }
            if (encounter.state() == Encounter.State.SPAWNING) return;
            long now = level.getGameTime();
            BlockPos target = position(context.getCompound("target"));
            boolean targetLoaded = level.hasChunkAt(target);
            boolean targetPresent = targetLoaded
                    && level.getBlockState(target).is(CreateDefendtheRealm.DEV_HQ.get());
            if (targetPresent) context.putBoolean("targetObserved", true);
            if (targetLoaded && !targetPresent && context.getBoolean("targetObserved")
                    && !context.getBoolean("targetDestroyed")) {
                context.putBoolean("targetDestroyed", true);
                context.putLong("targetDestroyedTick", now);
                context.putInt("postImpactTicksRemaining", com.createdtr.defendtherealm.Config.POST_IMPACT_LINGER.get());
            }
            boolean destructionConfirmed = context.getBoolean("targetDestroyed");
            if (!targetLoaded) {
                context.putString("controllerStatus", "HQ chunk temporarily unavailable");
                data.updateContext(context);
                return;
            }
            var container = SubLevelContainer.getContainer(level);
            var candidate = container == null || !context.hasUUID("rootVehicle") ? null : container.getSubLevel(context.getUUID("rootVehicle"));
            if (!(candidate instanceof ServerSubLevel ship) || ship.isRemoved()) {
                DRIVES.remove(level);
                int missing = context.getInt("missingTicks") + 1; context.putInt("missingTicks", missing);
                if (missing > 200) encounter.terminate(Encounter.Reason.DEFEATED);
                data.updateContext(context); return;
            }
            context.putInt("missingTicks", 0);
            Vec3 current = new Vec3(ship.logicalPose().position().x(), ship.logicalPose().position().y(), ship.logicalPose().position().z());
            var combatProfile = VehicleCombatProfiles.requireForTemplate(context.getString("template"));
            WeaponRuntime.initialize(context, combatProfile);
            var weaponProfile = combatProfile.primaryWeapon();
            CannonMountBlockEntity weaponMount;
            try {
                weaponMount = bindPrimaryMount(ship, combatProfile, context);
            } catch (IllegalStateException missingWeapon) {
                context.putString("controllerFailure", missingWeapon.getMessage());
                context.putString("controllerStatus", "Weapon separated or destroyed; retaining attacker");
                context.putBoolean("combatIncapable", true);
                context.putBoolean("controllerSuspended", true);
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                data.updateContext(context);
                return;
            }
            var weaponReadiness = PrototypeWeapon.readiness(ship, combatProfile.weapons().size());
            if (context.getBoolean("recoveryPending")) {
                if (!context.contains("recoveryStartedTick")) context.putLong("recoveryStartedTick", now);
                context.putString("weaponReadiness", weaponReadiness.name());
                if (weaponReadiness != PrototypeWeapon.Readiness.READY) {
                    DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                    EncounterChunkTickets.update(level, data, ship, BlockPos.containing(current), BlockPos.containing(current), target);
                    if (now - context.getLong("recoveryStartedTick") >= com.createdtr.defendtherealm.Config.RECOVERY_GRACE_TICKS.get()) {
                        context.putString("recoveryFailure", "Nested CBC runtime did not become ready: " + weaponReadiness);
                        context.putBoolean("combatIncapable", true);
                    }
                    data.updateContext(context);
                    return;
                }
                context.putBoolean("recoveryPending", false);
                context.putLong("recoveryCompletedTick", now);
                context.remove("recoveryFailure");
            } else if (weaponReadiness != PrototypeWeapon.Readiness.READY) {
                context.putString("controllerFailure", "Autocannon unavailable during active encounter: " + weaponReadiness);
                context.putBoolean("combatIncapable", true);
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                data.updateContext(context);
                return;
            }
            PrototypeWeapon.stopAll(ship);
            if (destructionConfirmed) {
                int remaining = postImpactTicksRemaining(now, context.getLong("targetDestroyedTick"),
                        com.createdtr.defendtherealm.Config.POST_IMPACT_LINGER.get());
                context.putInt("postImpactTicksRemaining", remaining);
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                if (remaining == 0) {
                    encounter.terminate(Encounter.Reason.TARGET_DESTROYED);
                    DRIVES.remove(level);
                }
                data.updateContext(context);
                return;
            }
            if (!context.contains("approachStarted")) context.putLong("approachStarted", now);
            if (now - context.getLong("approachStarted") > 7200
                    || encounter.state() == Encounter.State.APPROACHING && now - context.getLong("approachStarted") > 3600) {
                context.putInt("elapsedTacticalTimeouts", context.getInt("elapsedTacticalTimeouts") + 1);
                context.putLong("approachStarted", now);
                context.remove("route");
                context.putString("controllerStatus", "Tactical timeout; replanning from current position");
            }
            Vec3 muzzle = PrototypeWeapon.muzzle(ship, weaponMount);
            boolean hqInRange = weaponProfile.range().contains(muzzle.distanceTo(Vec3.atCenterOf(target)));
            boolean hqClear = hqInRange && PrototypeWeapon.assessClearShot(ship, weaponMount,
                    Vec3.atCenterOf(target), target, context, weaponProfile.trajectory());
            AssaultTargeting.Target combatTarget = AssaultTargeting.resolveOrSelect(level, current, muzzle, target,
                    context, weaponProfile.range(), hqClear,
                    possibleTarget -> targetFeasible(ship, weaponMount, weaponProfile, possibleTarget));
            if (combatTarget == null) {
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                context.putString("controllerStatus", "No current weapon target; retaining assault");
                data.updateContext(context);
                return;
            }
            Envelope envelope = envelope(ship, context);
            List<BlockPos> route = java.util.Arrays.stream(context.getLongArray("route")).mapToObj(BlockPos::of).toList();
            int cursor = context.getInt("routeCursor");
            if (route.isEmpty()) {
                // The HQ is always the strategic destination. Combat targets
                // encountered along this corridor may interrupt firing briefly,
                // but never replace or invalidate the cached HQ approach.
                route = initialRoute(level, encounter.id(), current, target, envelope, weaponProfile.range());
                if (route.isEmpty()) {
                    context.putInt("routePlanFailures", context.getInt("routePlanFailures") + 1);
                    context.putString("controllerStatus", "HQ route unavailable; retrying");
                    DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                    EncounterChunkTickets.update(level, data, ship, BlockPos.containing(current),
                            BlockPos.containing(current), target);
                    data.updateContext(context);
                    return;
                }
                cursor = Math.min(1, route.size() - 1);
                context.putLong("lastProgress", now); context.putDouble("progressDistance", Double.MAX_VALUE);
            }
            if (cursor < 0 || cursor >= route.size()) throw new IllegalArgumentException("Invalid saved route cursor");
            var navigation = RoutePlanners.get(combatProfile.family(), combatProfile.navigationProfile());
            while (cursor < route.size() - 1 && (current.distanceTo(Vec3.atCenterOf(route.get(cursor))) < 3.0
                    || cursor + 1 < route.size() && navigation.hasClearSegment(level, current,
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
                        && navigation.hasClearSegment(level, current, lookahead, envelope)) {
                    cursor = i;
                    context.putLong("lastProgress", now);
                    context.putDouble("progressDistance", Double.MAX_VALUE);
                    break;
                }
            }
            Vec3 next = Vec3.atCenterOf(route.get(cursor));
            observeHeadingProgress(ship, next, context, "approachHeading", "lastProgress", now);
            double distance = current.distanceTo(next);
            if (context.getDouble("progressDistance") - distance >= 1) {
                context.putDouble("progressDistance", distance); context.putLong("lastProgress", now);
            }
            boolean tacticalManeuver = context.getLongArray("maneuverRoute").length > 0;
            boolean obstructed = !tacticalManeuver && now % 10 == 0
                    && !navigation.hasClearSegment(level, current, next, envelope);
            boolean stuck = !tacticalManeuver && distance > 1 && now - context.getLong("lastProgress") >= 100;
            if (obstructed || stuck) {
                DRIVES.put(level, new Drive(encounter.id(), ship, current, target, false));
                if (now < context.getLong("nextRepair")) { data.updateContext(context); return; }
                context.putLong("nextRepair", now + 40);
                var repaired = repair(level, current, route, cursor, envelope, navigation);
                context.putInt("repairs", context.getInt("repairs") + 1);
                if (repaired.isEmpty()) {
                    context.putInt("failedRepairs", context.getInt("failedRepairs") + 1);
                    context.putString("controllerStatus", "Route repair failed; retaining assault and retrying");
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
            if (cursor == route.size() - 1 && reached(current, next)
                    && encounter.state() == Encounter.State.APPROACHING)
                encounter.advance(Encounter.State.ENGAGING);
            boolean opportunity = combatTarget.type() != AssaultTargeting.Type.HQ
                    && weaponProfile.range().contains(muzzle.distanceTo(combatTarget.aim()));
            if (opportunity && encounter.state() == Encounter.State.APPROACHING)
                encounter.advance(Encounter.State.ENGAGING);
            // Independent turrets never steer the hull at their tactical target.
            DRIVES.put(level, new Drive(encounter.id(), ship, next, target, false));
            boolean atFiringPoint = opportunity || encounter.combatEnabled()
                    && (tacticalManeuver || reached(current, next));
            TacticalResult tactical = tickTactics(level, ship, weaponMount, weaponProfile, encounter, context,
                    combatTarget, current, route, cursor, envelope, atFiringPoint);
            route = tactical.route();
            cursor = tactical.cursor();
            if (tactical.destination() != null)
                DRIVES.put(level, new Drive(encounter.id(), ship, tactical.destination(), target, false));
            tickAdditionalWeapons(level, ship, combatProfile, weaponMount, current, target, context);
            sendTurretPoses(level, ship, current, context);
            context.putLongArray("route", route.stream().mapToLong(BlockPos::asLong).toArray());
            context.putInt("routeCursor", cursor);
            sendDebug(level, encounter, context, combatTarget, current, route, cursor);
            data.updateContext(context);
            EncounterChunkTickets.update(level, data, ship, BlockPos.containing(current), route.get(cursor), target);
        } catch (RuntimeException ex) {
            context.putString("controllerFailure", ex.toString());
            context.putInt("controllerFailures", context.getInt("controllerFailures") + 1);
            context.putBoolean("controllerSuspended", true);
            data.updateContext(context);
            CreateDefendtheRealm.LOGGER.error("HQ assault {} suspended without cleanup", encounter.id(), ex);
        }
    }

    private static CannonMountBlockEntity bindPrimaryMount(ServerSubLevel ship,
            com.createdtr.defendtherealm.combat.VehicleCombatProfile profile, CompoundTag context) {
        return bindMounts(ship, profile, context).get(profile.primaryWeapon().id());
    }

    private static Map<String, CannonMountBlockEntity> bindMounts(ServerSubLevel ship,
            com.createdtr.defendtherealm.combat.VehicleCombatProfile profile, CompoundTag context) {
        var mounts = PrototypeWeapon.findAll(ship);
        if (mounts.size() != profile.weapons().size())
            throw new IllegalStateException("Combat profile expects " + profile.weapons().size()
                    + " cannon mount(s), found " + mounts.size());
        Map<String, CannonMountBlockEntity> result = new java.util.LinkedHashMap<>();
        for (var weapon : profile.weapons()) {
            CannonMountBlockEntity mount;
            if (weapon.mountOffset() == null) {
                if (mounts.size() != 1) throw new IllegalStateException("Multiple mounts require explicit mount_offset bindings");
                mount = mounts.getFirst();
            } else {
                mount = mounts.stream().filter(value -> value.getBlockPos().equals(weapon.mountOffset())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Missing configured mount " + weapon.mountOffset()));
            }
            if (result.containsValue(mount)) throw new IllegalStateException("Two weapon profiles bind the same mount");
            result.put(weapon.id(), mount);
            CompoundTag state = WeaponRuntime.state(context, weapon.id());
            state.putLong("mountPosition", mount.getBlockPos().asLong());
            if (mount.getContraption() != null) state.putUUID("runtimeEntity", mount.getContraption().getUUID());
        }
        return Map.copyOf(result);
    }

    private static void tickAdditionalWeapons(ServerLevel level, ServerSubLevel ship,
            com.createdtr.defendtherealm.combat.VehicleCombatProfile profile, CannonMountBlockEntity primary,
            Vec3 current, BlockPos hq, CompoundTag context) {
        if (profile.weapons().size() == 1) return;
        Map<String, CannonMountBlockEntity> mounts = bindMounts(ship, profile, context);
        for (var weapon : profile.weapons()) {
            CannonMountBlockEntity mount = mounts.get(weapon.id());
            if (mount == primary) continue;
            if (mount == null || mount.getContraption() == null) throw new IllegalStateException("Weapon unavailable: " + weapon.id());
            CompoundTag state = WeaponRuntime.state(context, weapon.id());
            Vec3 muzzle = PrototypeWeapon.muzzle(ship, mount);
            boolean hqInRange = weapon.range().contains(muzzle.distanceTo(Vec3.atCenterOf(hq)));
            boolean hqClear = hqInRange && PrototypeWeapon.assessClearShot(ship, mount, Vec3.atCenterOf(hq), hq,
                    state, weapon.trajectory());
            AssaultTargeting.Target selected = AssaultTargeting.resolveOrSelect(level, current, muzzle, hq, state,
                    weapon.range(), hqClear, candidate -> targetFeasible(ship, mount, weapon, candidate));
            if (selected == null) { PrototypeWeapon.stop(mount); continue; }
            boolean inRange = weapon.range().contains(muzzle.distanceTo(selected.aim()));
            boolean breach = selected.type() == AssaultTargeting.Type.HQ && !hqClear && weapon.breachCapable();
            long blocker = state.contains("shotBlockedAt") ? state.getLong("shotBlockedAt") : Long.MIN_VALUE;
            int before = context.getInt("shots");
            boolean breachShot = breach && blocker != Long.MIN_VALUE;
            tickWeapon(ship, mount, weapon, selected, context, inRange, breachShot);
            if (breachShot && context.getInt("shots") > before) {
                WeaponRuntime.incrementBlocker(state, blocker);
                state.putInt("breachShots", state.getInt("breachShots") + 1);
            }
        }
    }

    private static boolean targetFeasible(ServerSubLevel ship, CannonMountBlockEntity mount,
            com.createdtr.defendtherealm.combat.WeaponProfile weapon, AssaultTargeting.Target target) {
        CompoundTag probe = new CompoundTag();
        BlockPos accepted = target.type() == AssaultTargeting.Type.PLAYER ? null : target.block();
        return PrototypeWeapon.assessClearShot(ship, mount, target.aim(), accepted, probe, weapon.trajectory());
    }

    private static void sendDebug(ServerLevel level, Encounter encounter, CompoundTag context,
            AssaultTargeting.Target target, Vec3 ship, List<BlockPos> route, int cursor) {
        if (level.getGameTime() % 10 != 0) return;
        long[] maneuver = context.getLongArray("maneuverRoute");
        long[] displayedRoute = maneuver.length > 0
                ? maneuver : route.stream().mapToLong(BlockPos::asLong).toArray();
        int displayedCursor = maneuver.length > 0 ? context.getInt("maneuverCursor") : cursor;
        boolean hasOrbit = context.contains("orbitCandidate");
        boolean hasBlocker = context.contains("shotBlockedAt") && !context.getBoolean("shotBlockedSelf")
                && !"unloaded".equals(context.getString("shotBlockedBy"));
        var payload = new com.createdtr.defendtherealm.network.AssaultDebugPayload(encounter.id(),
                level.dimension().location().toString(), context.getString("tacticalPhase"), target.type().name(),
                level.getGameTime(), ship.x, ship.y, ship.z, target.aim().x, target.aim().y, target.aim().z,
                displayedRoute, displayedCursor, hasOrbit,
                hasOrbit ? context.getLong("orbitCandidate") : 0L, hasBlocker,
                hasBlocker ? context.getLong("shotBlockedAt") : 0L);
        for (var player : level.players()) if (player.hasPermissions(2)) PacketDistributor.sendToPlayer(player, payload);
    }

    private static void sendTurretPoses(ServerLevel level, ServerSubLevel ship, Vec3 current, CompoundTag context) {
        var poses = new java.util.ArrayList<com.createdtr.defendtherealm.network.TurretPosePayload.Pose>();
        int hash = 1;
        for (CannonMountBlockEntity mount : PrototypeWeapon.findAll(ship)) {
            var entity = mount.getContraption();
            if (entity == null) continue;
            poses.add(new com.createdtr.defendtherealm.network.TurretPosePayload.Pose(
                    mount.getBlockPos().asLong(), entity.getId(), entity.getUUID(), entity.yaw, entity.pitch));
            hash = 31 * hash + Long.hashCode(mount.getBlockPos().asLong());
            hash = 31 * hash + Float.floatToIntBits(entity.yaw);
            hash = 31 * hash + Float.floatToIntBits(entity.pitch);
        }
        long now = level.getGameTime();
        if (poses.isEmpty() || hash == context.getInt("lastTurretPoseHash")
                && now - context.getLong("lastTurretPosePacketTick") < 20) return;
        context.putInt("lastTurretPoseHash", hash);
        context.putLong("lastTurretPosePacketTick", now);
        var payload = new com.createdtr.defendtherealm.network.TurretPosePayload(
                level.dimension().location().toString(), ship.getUniqueId(), now, poses);
        PacketDistributor.sendToPlayersTrackingChunk(level,
                new net.minecraft.world.level.ChunkPos(BlockPos.containing(current)), payload);
    }

    private static TacticalResult tickTactics(ServerLevel level, ServerSubLevel ship, CannonMountBlockEntity mount,
            com.createdtr.defendtherealm.combat.WeaponProfile weapon, Encounter encounter,
            CompoundTag context, AssaultTargeting.Target target, Vec3 current, List<BlockPos> route,
            int cursor, Envelope envelope, boolean atFiringPoint) {
        if (!atFiringPoint) {
            tickWeapon(ship, mount, weapon, target, context, false, false);
            return new TacticalResult(route, cursor, Vec3.atCenterOf(route.get(cursor)));
        }
        long now = level.getGameTime();
        if (target.type() != AssaultTargeting.Type.HQ) {
            TacticalResult movement = advanceManeuver(level, ship, encounter, context, current, route, cursor, envelope);
            if (movement == null) movement = new TacticalResult(route, cursor, Vec3.atCenterOf(route.get(cursor)));
            int before = context.getInt("shots");
            tickWeapon(ship, mount, weapon, target, context, true, false);
            int consumed = Math.max(0, context.getInt("shots") - before);
            if (consumed > 0) context.putInt("opportunityShots", context.getInt("opportunityShots") + consumed);
            if (!context.getBoolean("clearShot")) {
                context.putString("lastRejectedTarget", target.key());
                context.putString("lastRejectedReason", "opportunity_target_not_in_line_of_sight");
                AssaultTargeting.rejectCurrent(context);
            } else {
                context.putString("tacticalPhase", "OPPORTUNITY_FIRE");
            }
            if (weapon.behavior() != com.createdtr.defendtherealm.combat.WeaponBehavior.INDEPENDENT_TURRET
                    && consumed == 0 && context.getBoolean("clearShot") && context.getDouble("aimError") > 1) {
                if (!context.contains("playerManeuverStarted")) {
                    context.putLong("playerManeuverStarted", now);
                    Vec3 horizontal = current.subtract(target.aim()).multiply(1, 0, 1);
                    if (horizontal.lengthSqr() < 0.01) horizontal = new Vec3(1, 0, 0);
                    Vec3 goal = current.add(horizontal.normalize().scale(16))
                            .add(0, Math.clamp(target.aim().y - current.y, -16, 16), 0);
                    context.putDouble("playerManeuverX", goal.x);
                    context.putDouble("playerManeuverY", goal.y);
                    context.putDouble("playerManeuverZ", goal.z);
                }
                if (now - context.getLong("playerManeuverStarted") < 120) {
                    Vec3 goal = new Vec3(context.getDouble("playerManeuverX"), context.getDouble("playerManeuverY"),
                            context.getDouble("playerManeuverZ"));
                    var planner = RoutePlanners.get(VehicleFamily.HOVER_AIRSHIP, "hover_airship");
                    if (planner.hasClearSegment(level, current, goal, envelope)) {
                        context.putString("tacticalPhase", "PLAYER_FIRING_MANEUVER");
                        return new TacticalResult(route, cursor, goal);
                    }
                } else {
                    context.putString("lastRejectedTarget", target.key());
                    context.putString("lastRejectedReason", "player_maneuver_timeout");
                    AssaultTargeting.rejectCurrent(context);
                }
            } else {
                context.remove("playerManeuverStarted");
                context.remove("playerManeuverX"); context.remove("playerManeuverY"); context.remove("playerManeuverZ");
            }
            // An independently traversing weapon opportunistically engages
            // without replacing the strategic route with a stationary hold.
            return movement;
        }
        boolean breachIntent = target.type() == AssaultTargeting.Type.HQ && weapon.breachCapable()
                && context.contains("shotBlockedAt") && !context.getBoolean("shotBlockedSelf")
                && !"unloaded".equals(context.getString("shotBlockedBy"));
        long blockerBeforeShot = breachIntent ? context.getLong("shotBlockedAt") : Long.MIN_VALUE;
        int shotsBeforeTick = context.getInt("shots");
        tickWeapon(ship, mount, weapon, target, context, true, breachIntent);
        boolean clearShot = context.getBoolean("clearShot");
        if (clearShot) {
            context.putString("tacticalPhase", "ENGAGE");
        }
        boolean usableBlocker = context.contains("shotBlockedAt") && !context.getBoolean("shotBlockedSelf")
                && !"unloaded".equals(context.getString("shotBlockedBy"));
        if (target.type() == AssaultTargeting.Type.HQ && weapon.breachCapable() && usableBlocker) {
            long blocker = context.getLong("shotBlockedAt");
            CompoundTag weaponState = WeaponRuntime.state(context, weapon.id());
            int consumed = Math.max(0, context.getInt("shots") - shotsBeforeTick);
            if (consumed > 0) {
                int blockerShots = WeaponRuntime.incrementBlocker(weaponState,
                        blockerBeforeShot == Long.MIN_VALUE ? blocker : blockerBeforeShot);
                context.putInt("breachShots", context.getInt("breachShots") + consumed);
                context.putInt("currentBlockerShots", blockerShots);
                context.putLong("lastBreachTarget", blocker);
            }
            context.putString("tacticalPhase", "ORBIT_AND_BREACH");
        }

        TacticalResult activeManeuver = advanceManeuver(level, ship, encounter, context, current, route, cursor, envelope);
        if (activeManeuver != null) return activeManeuver;
        if (clearShot) {
            context.putInt("orbitAttempts", 0);
            context.remove("orbitSearchStartedTick");
            context.remove("maneuverHoldX"); context.remove("maneuverHoldY"); context.remove("maneuverHoldZ");
            return new TacticalResult(route, cursor, current);
        }
        if (now < context.getLong("nextLosSearch"))
            return new TacticalResult(route, cursor, holdDestination(context, current));
        if (!context.contains("orbitSearchStartedTick")) context.putLong("orbitSearchStartedTick", now);
        if (now - context.getLong("orbitSearchStartedTick") >= MANEUVER_SEARCH_DEADLINE_TICKS) {
            context.putString("lastOrbitFailure", "orbit_progress_timeout");
            context.putInt("orbitCycles", context.getInt("orbitCycles") + 1);
            context.putInt("orbitAttempts", 0);
            context.putLong("orbitSearchStartedTick", now);
            clearManeuver(context);
            return new TacticalResult(route, cursor, holdDestination(context, current));
        }
        context.putLong("nextLosSearch", now + com.createdtr.defendtherealm.Config.LOS_SEARCH_INTERVAL.get());
        int attempt = context.getInt("orbitAttempts");
        int rings = 3;
        int totalAttempts = 16 * rings;
        int searches = 0;
        while (attempt < totalAttempts && searches++ < ORBIT_SEARCHES_PER_TICK) {
            double angle = (attempt % 16) * Math.PI * 2 / 16.0;
            double fraction = attempt / 16 / (double) (rings - 1);
            double radius = weapon.range().preferredMaximum()
                    - (weapon.range().preferredMaximum() - weapon.range().preferredMinimum()) * fraction;
            BlockPos goal = BlockPos.containing(target.aim().x + Math.cos(angle) * radius,
                    current.y, target.aim().z + Math.sin(angle) * radius);
            attempt++;
            context.putInt("orbitAttempts", attempt);
            context.putLong("orbitCandidate", goal.asLong());
            List<BlockPos> moved = RoutePlanners.get(VehicleFamily.HOVER_AIRSHIP, "hover_airship")
                    .plan(level, BlockPos.containing(current), goal, envelope);
            boolean reachesCandidate = !moved.isEmpty()
                    && Vec3.atCenterOf(moved.getLast()).distanceTo(Vec3.atCenterOf(goal)) <= WAYPOINT_REACHED_DISTANCE;
            boolean meaningful = !moved.isEmpty()
                    && Vec3.atCenterOf(moved.getLast()).distanceTo(current) > MINIMUM_MANEUVER_DISTANCE;
            if (reachesCandidate && meaningful) {
                int movedCursor = Math.min(1, moved.size() - 1);
                context.putLongArray("maneuverRoute", moved.stream().mapToLong(BlockPos::asLong).toArray());
                context.putInt("maneuverCursor", movedCursor);
                context.putLong("maneuverStartedTick", now);
                resetManeuverProgress(context, now);
                context.remove("maneuverHoldX"); context.remove("maneuverHoldY"); context.remove("maneuverHoldZ");
                if (!"ORBIT_AND_BREACH".equals(context.getString("tacticalPhase")))
                    context.putString("tacticalPhase", "LOS_SEARCH");
                return new TacticalResult(route, cursor, Vec3.atCenterOf(moved.get(movedCursor)));
            }
            context.putString("lastOrbitFailure", reachesCandidate ? "candidate_too_close" : "candidate_unreachable");
        }
        if (attempt < totalAttempts) {
            context.putLong("nextLosSearch", now + 1);
            return new TacticalResult(route, cursor, holdDestination(context, current));
        }
        context.putString("lastOrbitFailure", "range_orbit_exhausted");
        context.putInt("orbitCycles", context.getInt("orbitCycles") + 1);
        context.putInt("orbitAttempts", 0);
        context.putLong("orbitSearchStartedTick", now);
        CreateDefendtheRealm.LOGGER.info("Assault {} exhausted {} orbit candidates for {}",
                encounter.id(), totalAttempts, target.key());
        clearManeuver(context);
        return new TacticalResult(route, cursor, holdDestination(context, current));
    }

    private static TacticalResult advanceManeuver(ServerLevel level, ServerSubLevel ship, Encounter encounter,
            CompoundTag context, Vec3 current, List<BlockPos> route, int cursor, Envelope envelope) {
        long now = level.getGameTime();
        long[] maneuverValues = context.getLongArray("maneuverRoute");
        if (maneuverValues.length > 0) {
            List<BlockPos> maneuver = java.util.Arrays.stream(maneuverValues).mapToObj(BlockPos::of).toList();
            int maneuverCursor = Math.clamp(context.getInt("maneuverCursor"), 0, maneuver.size() - 1);
            var planner = RoutePlanners.get(VehicleFamily.HOVER_AIRSHIP, "hover_airship");
            while (maneuverCursor < maneuver.size() - 1 && (reached(current, Vec3.atCenterOf(maneuver.get(maneuverCursor)))
                    || maneuverCursor + 1 < maneuver.size()
                            && planner.hasClearSegment(level, current, Vec3.atCenterOf(maneuver.get(maneuverCursor + 1)), envelope)
                            && current.distanceTo(Vec3.atCenterOf(maneuver.get(maneuverCursor + 1)))
                                    < current.distanceTo(Vec3.atCenterOf(maneuver.get(maneuverCursor))))) {
                maneuverCursor++;
                resetManeuverProgress(context, now);
            }
            for (int i = maneuver.size() - 1; i > maneuverCursor; i--) {
                Vec3 lookahead = Vec3.atCenterOf(maneuver.get(i));
                if (current.distanceTo(lookahead) <= ROUTE_LOOKAHEAD_DISTANCE
                        && planner.hasClearSegment(level, current, lookahead, envelope)) {
                    maneuverCursor = i;
                    resetManeuverProgress(context, now);
                    break;
                }
            }
            context.putInt("maneuverCursor", maneuverCursor);
            Vec3 destination = Vec3.atCenterOf(maneuver.get(maneuverCursor));
            observeHeadingProgress(ship, destination, context,
                    "maneuverHeading", "maneuverLastProgress", now);
            double distance = current.distanceTo(destination);
            if (!context.contains("maneuverLastProgress")) resetManeuverProgress(context, now);
            if (!context.contains("maneuverProgressDistance")
                    || context.getDouble("maneuverProgressDistance") - distance >= 1) {
                context.putDouble("maneuverProgressDistance", distance);
                context.putLong("maneuverLastProgress", now);
            }
            context.putDouble("maneuverWaypointDistance", distance);
            context.putLong("maneuverDestination", maneuver.get(maneuverCursor).asLong());
            boolean obstructed = now % 10 == 0 && !planner.hasClearSegment(level, current, destination, envelope);
            boolean stuck = !reached(current, destination)
                    && now - context.getLong("maneuverLastProgress") >= MANEUVER_STUCK_TICKS;
            if (!obstructed && !stuck && (maneuverCursor < maneuver.size() - 1 || !reached(current, destination)))
                return new TacticalResult(route, cursor, destination);
            if (obstructed || stuck) {
                context.putString("lastOrbitFailure", obstructed ? "segment_obstructed" : "stalled");
                context.putInt("maneuverStalledTicks",
                        (int) Math.min(Integer.MAX_VALUE, now - context.getLong("maneuverLastProgress")));
                clearManeuver(context);
                context.putLong("nextLosSearch", now);
                CreateDefendtheRealm.LOGGER.info("Assault {} rejected orbit candidate {}: {}",
                        encounter.id(), context.getLong("orbitCandidate"), context.getString("lastOrbitFailure"));
            } else {
                context.putString("lastOrbitFailure", "candidate_reached_without_line_of_sight");
                clearManeuver(context);
                return new TacticalResult(route, cursor, holdDestination(context, current));
            }
        }
        return null;
    }

    private static void observeHeadingProgress(ServerSubLevel ship, Vec3 destination, CompoundTag context,
            String key, String timer, long now) {
        Vector3d forward = ship.logicalPose().transformNormal(new Vector3d(0, 0, -1));
        Vector3d to = new Vector3d(destination.x, 0, destination.z)
                .sub(ship.logicalPose().position().x(), 0, ship.logicalPose().position().z());
        double error = to.lengthSquared() < 1.0e-6 ? 0 : Math.abs(Math.toDegrees(Math.atan2(
                forward.z * to.x - forward.x * to.z, forward.x * to.x + forward.z * to.z)));
        recordHeadingProgress(context, key, timer, BlockPos.containing(destination).asLong(), error, now);
    }

    static void recordHeadingProgress(CompoundTag context, String key, String timer,
            long destination, double error, long now) {
        if (!context.contains(key + "Destination") || context.getLong(key + "Destination") != destination) {
            context.putLong(key + "Destination", destination);
            context.putDouble(key, error);
            context.putLong(timer, now);
        } else if (context.getDouble(key) - error >= 2) {
            // Only a new best heading counts; stationary or oscillating turns
            // still expire through the normal bounded stall timeout.
            context.putDouble(key, error);
            context.putLong(timer, now);
        }
    }

    static boolean reached(Vec3 current, Vec3 destination) {
        return current.distanceTo(destination) < WAYPOINT_REACHED_DISTANCE;
    }

    private static void resetManeuverProgress(CompoundTag context, long now) {
        context.remove("maneuverHeadingDestination");
        context.remove("maneuverHeading");
        context.putLong("maneuverLastProgress", now);
        context.putDouble("maneuverProgressDistance", Double.MAX_VALUE);
        context.putInt("maneuverStalledTicks", 0);
    }

    private static void clearManeuver(CompoundTag context) {
        context.remove("maneuverHeadingDestination");
        context.remove("maneuverHeading");
        context.remove("maneuverRoute");
        context.remove("maneuverCursor");
        context.remove("maneuverDestination");
        context.remove("maneuverProgressDistance");
        context.remove("maneuverLastProgress");
    }

    private static Vec3 holdDestination(CompoundTag context, Vec3 current) {
        if (!context.contains("maneuverHoldX")) {
            context.putDouble("maneuverHoldX", current.x);
            context.putDouble("maneuverHoldY", current.y);
            context.putDouble("maneuverHoldZ", current.z);
        }
        return new Vec3(context.getDouble("maneuverHoldX"), context.getDouble("maneuverHoldY"),
                context.getDouble("maneuverHoldZ"));
    }

    private static void tickWeapon(ServerSubLevel ship, CannonMountBlockEntity mount,
            com.createdtr.defendtherealm.combat.WeaponProfile weapon, AssaultTargeting.Target target,
            CompoundTag context, boolean engage, boolean breach) {
        CompoundTag state = WeaponRuntime.state(context, weapon.id());
        context.putLong("nextShot", state.getLong("nextShot"));
        state.putString("targetType", target.type().name());
        state.putString("targetKey", target.key());
        state.putDouble("targetX", target.aim().x);
        state.putDouble("targetY", target.aim().y);
        state.putDouble("targetZ", target.aim().z);
        UUID player = target.type() == AssaultTargeting.Type.PLAYER
                ? UUID.fromString(target.key().substring("player:".length())) : null;
        BlockPos accepted = target.type() == AssaultTargeting.Type.PLAYER ? null : target.block();
        int before = context.getInt("shots");
        PrototypeWeapon.tick(ship, mount, target.aim(), target.velocity(), accepted, player, weapon.id(), context,
                engage, weapon.range().maximumFiringSpeed(), weapon.trajectory(), breach);
        state.putLong("nextShot", context.getLong("nextShot"));
        state.putDouble("aimError", context.getDouble("aimError"));
        state.putBoolean("clearShot", context.getBoolean("clearShot"));
        state.putBoolean("trajectoryReachable", context.getBoolean("trajectoryReachable"));
        state.putString("fireSolution", context.getString("fireSolution"));
        for (String key : List.of("desiredBarrelX", "desiredBarrelY", "desiredBarrelZ",
                "actualBarrelX", "actualBarrelY", "actualBarrelZ", "firingBarrelX", "firingBarrelY",
                "firingBarrelZ", "predictedImpactX", "predictedImpactY", "predictedImpactZ",
                "predictedFlightTicks", "firingAimError", "renderAimError", "projectileSpawnError",
                "projectileDirectionError"))
            if (context.contains(key)) state.putDouble(key, context.getDouble(key));
        state.putInt("shots", state.getInt("shots") + Math.max(0, context.getInt("shots") - before));
    }

    private static void tickDestroying(ServerLevel level, EncounterSavedData data, Encounter encounter,
            CompoundTag context) {
        ServerSubLevel ship = loadedOwned(level, encounter, context);
        if (ship == null) {
            int missing = context.getInt("destroyingMissingTicks") + 1;
            context.putInt("destroyingMissingTicks", missing);
            if (missing > 200 && context.getBoolean("sableReconciled")) {
                // Container-ready reconciliation proves these UUIDs are absent,
                // rather than merely outside the currently loaded chunk set.
                encounter.owned().forEach(encounter::acknowledgeRemoval);
                encounter.advance(Encounter.State.CLEANING_UP);
            }
            data.updateContext(context);
            return;
        }
        PrototypeWeapon.stopAll(ship);
        long now = level.getGameTime();
        if (!context.contains("defeatTick")) context.putLong("defeatTick", now);
        long elapsed = Math.max(0, now - context.getLong("defeatTick"));
        Vec3 current = new Vec3(ship.logicalPose().position().x(), ship.logicalPose().position().y(),
                ship.logicalPose().position().z());
        boolean grounded = hasGroundSupport(level, bounds(ship));
        int timeout = com.createdtr.defendtherealm.Config.DEFEAT_GROUNDING_TIMEOUT.get();
        context.putBoolean("defeatGrounded", grounded);
        context.putInt("defeatTicksRemaining", (int) Math.max(0, timeout - elapsed));
        if (grounded || elapsed >= timeout) {
            finalBlast(level, encounter, context);
            DRIVES.remove(level);
            encounter.advance(Encounter.State.CLEANING_UP);
        } else {
            DRIVES.put(level, new Drive(encounter.id(), ship, current.add(0, -10, 0),
                    position(context.getCompound("target")), true));
        }
        data.updateContext(context);
        if (!grounded && elapsed < timeout)
            EncounterChunkTickets.update(level, data, ship, BlockPos.containing(current),
                    BlockPos.containing(current), position(context.getCompound("target")));
    }

    private static ServerSubLevel loadedOwned(ServerLevel level, Encounter encounter, CompoundTag context) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        if (context.hasUUID("rootVehicle")) {
            var root = container.getSubLevel(context.getUUID("rootVehicle"));
            if (root instanceof ServerSubLevel ship && !ship.isRemoved()) return ship;
        }
        for (UUID id : encounter.owned()) {
            var value = container.getSubLevel(id);
            if (value instanceof ServerSubLevel ship && !ship.isRemoved()) return ship;
        }
        return null;
    }

    private static boolean hasGroundSupport(ServerLevel level, AABB bounds) {
        int y = (int) Math.floor(bounds.minY - 0.2D);
        int minX = (int) Math.floor(bounds.minX);
        int maxX = (int) Math.floor(bounds.maxX);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxZ = (int) Math.floor(bounds.maxZ);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.hasChunkAt(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
        }
        return false;
    }

    private static void finalBlast(ServerLevel level, Encounter encounter, CompoundTag context) {
        var container = SubLevelContainer.getContainer(level);
        List<ServerSubLevel> fragments = container == null ? List.of() : container.getAllSubLevels().stream()
                .filter(ship -> !ship.isRemoved() && encounter.owned().contains(ship.getUniqueId()))
                .toList();
        List<AABB> areas = new ArrayList<>();
        for (ServerSubLevel fragment : fragments) {
            Vec3 center = new Vec3(fragment.logicalPose().position().x(), fragment.logicalPose().position().y(),
                    fragment.logicalPose().position().z());
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
            level.playSound(null, BlockPos.containing(center), SoundEvents.GENERIC_EXPLODE.value(),
                    SoundSource.HOSTILE, 4.0F, 0.8F);
            areas.add(bounds(fragment).inflate(2));
        }
        context.putInt("finalBlastFragments", fragments.size());
        AABB combined = areas.stream().reduce(AABB::minmax).orElse(null);
        for (var entity : combined == null ? List.<net.minecraft.world.entity.Entity>of()
                : level.getEntities(null, combined)) {
            if (areas.stream().noneMatch(area -> area.intersects(entity.getBoundingBox()))) continue;
            if (entity.getPersistentData().hasUUID("dtrEncounter")
                    && encounter.id().equals(entity.getPersistentData().getUUID("dtrEncounter"))
                    && !(entity instanceof Player)) {
                entity.discard();
            } else if (entity instanceof LivingEntity living && living.getHealth() > 1.0F) {
                living.hurt(level.damageSources().generic(), Math.min(6.0F, living.getHealth() - 1.0F));
            }
        }
        context.putBoolean("finalBlast", true);
        context.putLong("finalBlastTick", level.getGameTime());
    }

    private static AABB bounds(ServerSubLevel ship) {
        var bounds = ship.boundingBox();
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    public static int postImpactTicksRemaining(long now, long destroyedTick) {
        return postImpactTicksRemaining(now, destroyedTick, POST_IMPACT_LINGER_TICKS);
    }

    public static int postImpactTicksRemaining(long now, long destroyedTick, int lingerTicks) {
        long elapsed = now >= destroyedTick ? now - destroyedTick : 0;
        return (int) Math.max(0, lingerTicks - elapsed);
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
        return initialRoute(level, assault, start, target, envelope,
                new WeaponRangeProfile(8, 28, 40, 96, 1.5));
    }

    public static List<BlockPos> initialRoute(ServerLevel level, UUID assault, Vec3 start, BlockPos target,
            Envelope envelope, WeaponRangeProfile range) {
        var key = new RouteKey(assault, level.dimension().location().toString(), VehicleFamily.HOVER_AIRSHIP,
                "hover_airship", envelope, target,
                new BlockPos(((int) Math.floor(start.x)) >> 4, ((int) Math.floor(start.y)) >> 4, ((int) Math.floor(start.z)) >> 4),
                range.preferredRadius());
        List<BlockPos> cached = CACHE.get(key);
        var planner = RoutePlanners.get(VehicleFamily.HOVER_AIRSHIP, "hover_airship");
        if (cached != null && planner.hasClearSegment(level, start, Vec3.atCenterOf(cached.getFirst()), envelope)) return cached;
        double angle = Math.atan2(start.z - target.getZ(), start.x - target.getX());
        double step = Math.max(4, (range.preferredMaximum() - range.preferredMinimum()) / 2);
        for (double radius = range.preferredMaximum(); radius + 0.001 >= range.preferredMinimum(); radius -= step) {
            for (int i = 0; i < 8; i++) {
                double a = angle + i * Math.PI / 4;
                BlockPos goal = BlockPos.containing(target.getX() + Math.cos(a) * radius,
                        start.y, target.getZ() + Math.sin(a) * radius);
                List<BlockPos> route = planner.plan(level, BlockPos.containing(start), goal, envelope);
                if (!route.isEmpty()) { CACHE.put(key, route); return route; }
            }
        }
        return List.of();
    }
    private static List<BlockPos> repair(ServerLevel level, Vec3 current, List<BlockPos> route, int cursor,
            Envelope envelope, VehicleRoutePlanner planner) {
        int attempts = 0;
        for (int i = route.size() - 1; i >= cursor && attempts < 3; i--) {
            if (current.distanceTo(Vec3.atCenterOf(route.get(i))) > 32) continue;
            if (!planner.hasClearance(level, Vec3.atCenterOf(route.get(i)), envelope)) continue;
            attempts++;
            var connector = planner.plan(level, BlockPos.containing(current), route.get(i), envelope);
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
                || encounter.state() != Encounter.State.APPROACHING
                && encounter.state() != Encounter.State.ENGAGING
                && !(encounter.state() == Encounter.State.DESTROYING && drive.descending())) return;
        if (PHYSICS_SEEN.add(encounter.id())) CreateDefendtheRealm.LOGGER.info("Assisted physics active for {} timestep={}", encounter.id(), event.getTimeStep());
        var ship = drive.ship(); var handle = event.getPhysicsSystem().getPhysicsHandle(ship);
        if (handle == null || !handle.isValid()) return;
        Vector3d desired;
        if (drive.descending()) {
            desired = new Vector3d(0, -com.createdtr.defendtherealm.Config.DEFEAT_DESCENT_SPEED.get(), 0);
        } else {
            desired = new Vector3d(drive.destination().x, drive.destination().y, drive.destination().z)
                    .sub(ship.logicalPose().position());
            double distance = desired.length();
            if (distance <= HOLD_DEADBAND) desired.zero();
            else if (distance > 1.25) desired.normalize(1.25);
        }
        Vector3d velocity = new Vector3d(handle.getLinearVelocity());
        Vector3d velocityCorrection = new Vector3d(desired).sub(velocity);
        if (drive.descending()) {
            handle.addLinearAndAngularVelocity(velocityCorrection,
                    new Vector3d(handle.getAngularVelocity()).negate());
            return;
        }
        Vector3d forward = ship.logicalPose().transformNormal(new Vector3d(0, 0, -1));
        Vector3d up = ship.logicalPose().transformNormal(new Vector3d(0, 1, 0));
        // Vehicle heading follows its route. Weapon behavior owns combat
        // alignment independently and must never force a hover airship to
        // side-slip toward a lateral orbit waypoint.
        Vector3d to = new Vector3d(drive.destination().x, 0, drive.destination().z)
                .sub(ship.logicalPose().position().x(), 0, ship.logicalPose().position().z());
        double yaw = to.lengthSquared() < 1.0e-6 ? 0
                : Math.atan2(forward.z * to.x - forward.x * to.z, forward.x * to.x + forward.z * to.z);
        // Turn in place for large heading errors, then ramp translation in as
        // the hull aligns. Vertical defeat motion uses the separate branch.
        double absoluteYaw = Math.abs(yaw);
        if (absoluteYaw >= Math.toRadians(20)) desired.zero();
        else desired.mul(1.0 - absoluteYaw / Math.toRadians(20));
        velocityCorrection = new Vector3d(desired).sub(velocity);
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
