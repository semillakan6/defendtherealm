package com.createdtr.defendtherealm.integration.cbc;

import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.createdtr.defendtherealm.combat.BallisticFireControl;
import com.createdtr.defendtherealm.combat.FireSolution;
import com.createdtr.defendtherealm.combat.TrajectoryProfile;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;

/** Compiled CBC integration; setters are rate limited and firing remains in CBC's tick. */
public final class PrototypeWeapon {
    public enum Readiness { READY, MISSING_MOUNT, RESTORING, UNSUPPORTED }
    public record BarrelPose(Vec3 transformedOrigin, Vec3 barrelEnd, Vec3 projectileSpawn, Vec3 direction) {}
    private PrototypeWeapon() {}
    public static List<CannonMountBlockEntity> findAll(ServerSubLevel ship) {
        var result = new java.util.ArrayList<CannonMountBlockEntity>();
        var bounds = ship.getPlot().getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()))
            if (ship.getLevel().getBlockEntity(pos) instanceof CannonMountBlockEntity mount) result.add(mount);
        result.sort(Comparator.comparingLong(mount -> mount.getBlockPos().asLong()));
        return List.copyOf(result);
    }
    public static CannonMountBlockEntity find(ServerSubLevel ship) {
        List<CannonMountBlockEntity> mounts = findAll(ship);
        return mounts.isEmpty() ? null : mounts.getFirst();
    }
    public static void stop(CannonMountBlockEntity mount) {
        if (mount == null || mount.isRemoved()) return;
        boolean assembled = mount.getBlockState().getValue(CannonMountBlock.ASSEMBLY_POWERED);
        mount.onRedstoneUpdate(assembled, assembled, false, mount.getBlockState().getValue(CannonMountBlock.FIRE_POWERED), 0);
    }
    public static void stopAll(ServerSubLevel ship) {
        findAll(ship).forEach(PrototypeWeapon::stop);
    }
    public static Readiness readiness(ServerSubLevel ship) {
        return readiness(ship, 1);
    }
    public static Readiness readiness(ServerSubLevel ship, int expectedMounts) {
        List<CannonMountBlockEntity> mounts = findAll(ship);
        if (mounts.size() < expectedMounts) return Readiness.MISSING_MOUNT;
        if (mounts.size() > expectedMounts) return Readiness.UNSUPPORTED;
        for (CannonMountBlockEntity mount : mounts) {
            if (mount.isRemoved()) return Readiness.MISSING_MOUNT;
            var entity = mount.getContraption();
            if (entity == null) return Readiness.RESTORING;
            if (!(entity.getContraption() instanceof MountedAutocannonContraption)) return Readiness.UNSUPPORTED;
        }
        return Readiness.READY;
    }
    public static Vec3 muzzle(ServerSubLevel ship, CannonMountBlockEntity mount) {
        var entity = mount.getContraption();
        if (entity == null || !(entity.getContraption() instanceof MountedAutocannonContraption cannon))
            throw new IllegalStateException("Missing mounted autocannon");
        return barrelPose(ship, entity, cannon, 0).projectileSpawn();
    }
    public static BarrelPose barrelPose(
            ServerSubLevel ship,
            rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity entity,
            MountedAutocannonContraption cannon, float partialTicks) {
        Direction direction = cannon.initialOrientation();
        BlockPos end = cannon.getStartPos();
        for (int i = 0; i < 128 && cannon.getBlocks().containsKey(end.relative(direction)); i++) end = end.relative(direction);
        Vec3 origin = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), partialTicks);
        Vec3 barrelEnd = entity.toGlobalVector(Vec3.atCenterOf(end.relative(direction)), partialTicks);
        Vec3 vector = barrelEnd.subtract(origin);
        if (vector.lengthSqr() < 1.0e-9) throw new IllegalStateException("CBC returned a zero-length barrel transform");
        Vec3 localDirection = vector.normalize();
        // CBC constructs the projectile 1.5 blocks behind the post-barrel point,
        // then advances it one block along the barrel before the join event.
        // The entity position observed by NeoForge is therefore 0.5 blocks
        // behind that point and is the authoritative projectile origin.
        Vec3 localSpawn = barrelEnd.subtract(localDirection.scale(0.5));
        // CBC fires inside Sable's embedded level. Its contraption transform is
        // therefore in plot coordinates; targeting and diagnostics operate in
        // the parent world and must apply the ship pose exactly once.
        return new BarrelPose(
                ship.logicalPose().transformPosition(origin),
                ship.logicalPose().transformPosition(barrelEnd),
                ship.logicalPose().transformPosition(localSpawn),
                ship.logicalPose().transformNormal(localDirection).normalize());
    }
    public static void tick(ServerSubLevel ship, BlockPos target, CompoundTag context, boolean engage) {
        tick(ship, Vec3.atCenterOf(target), target, context, engage);
    }
    public static void tick(ServerSubLevel ship, Vec3 target, BlockPos acceptedHit, CompoundTag context, boolean engage) {
        tick(ship, find(ship), target, Vec3.ZERO, acceptedHit, null, "main_turret", context, engage, 0.35,
                new TrajectoryProfile(com.createdtr.defendtherealm.combat.TrajectoryType.DIRECT_LOW_ARC, 4, 0, 0, 200));
    }
    public static void tick(ServerSubLevel ship, CannonMountBlockEntity mount, Vec3 target, Vec3 targetVelocity,
            BlockPos acceptedHit, UUID targetPlayer, String weaponId, CompoundTag context, boolean engage,
            double maximumFiringSpeed, TrajectoryProfile trajectory) {
        tick(ship, mount, target, targetVelocity, acceptedHit, targetPlayer, weaponId, context, engage,
                maximumFiringSpeed, trajectory, false);
    }
    public static void tick(ServerSubLevel ship, CannonMountBlockEntity mount, Vec3 target, Vec3 targetVelocity,
            BlockPos acceptedHit, UUID targetPlayer, String weaponId, CompoundTag context, boolean engage,
            double maximumFiringSpeed, TrajectoryProfile configuredTrajectory, boolean allowWorldObstruction) {
        if (mount == null || mount.getContraption() == null) throw new IllegalStateException("Autocannon mount lost");
        stop(mount);
        var entity = mount.getContraption();
        if (!(entity.getContraption() instanceof MountedAutocannonContraption cannon)) throw new IllegalStateException("Unsupported cannon");
        // Advance the restored nested contraption once per server tick so its
        // breech cooldown and internal block entities remain live under Sable.
        cannon.tick(ship.getLevel(), entity);
        ShotAccounting.flush(ship.getLevel(), cannon, context);
        context.putUUID("cannonEntity", entity.getUUID());
        context.putString("activeWeapon", weaponId);
        context.putBoolean("mountRunning", mount.isRunning());
        BarrelPose initialPose = barrelPose(ship, entity, cannon, 0);
        Vec3 muzzle = initialPose.projectileSpawn();
        double projectileSpeed = context.contains("observedProjectileSpeed")
                ? Math.max(0.1, context.getDouble("observedProjectileSpeed")) : configuredTrajectory.projectileSpeed();
        var trajectory = new TrajectoryProfile(configuredTrajectory.type(), projectileSpeed,
                configuredTrajectory.gravityPerTick(), configuredTrajectory.dragPerTick(),
                configuredTrajectory.maximumFlightTicks());
        // CBC/Sable publishes the projectile's parent-world launch velocity.
        // Do not subtract the rigid body's velocity here: on this pinned stack
        // that double-compensates platform motion and aims below moving ships.
        FireSolution solution = BallisticFireControl.solve(muzzle, target, targetVelocity, trajectory);
        context.putString("trajectoryType", trajectory.type().name());
        context.putString("fireSolution", solution.reason());
        context.putBoolean("trajectoryReachable", solution.reachable());
        if (!solution.reachable()) {
            context.putBoolean("clearShot", false);
            context.putDouble("aimError", 180);
            context.putBoolean("breachShotAuthorized", false);
            return;
        }
        Vec3 local = ship.logicalPose().transformNormalInverse(solution.launchDirection()).normalize();
        Vec3 current = entity.applyRotation(Vec3.atLowerCornerOf(cannon.initialOrientation().getNormal()), 1).normalize();
        double yawError = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-local.x, local.z) - Math.atan2(-current.x, current.z)));
        double pitchError = Math.toDegrees(Math.asin(Mth.clamp(local.y, -1, 1)) - Math.asin(Mth.clamp(current.y, -1, 1)));
        // Stabilize the previous world-space barrel direction against hull motion
        // before applying the turret's own bounded target-tracking slew.
        double yawCompensation = 0;
        double pitchCompensation = 0;
        if (context.contains("actualBarrelX")) {
            Vec3 previousWorld = new Vec3(context.getDouble("actualBarrelX"),
                    context.getDouble("actualBarrelY"), context.getDouble("actualBarrelZ"));
            Vec3 stabilized = ship.logicalPose().transformNormalInverse(previousWorld).normalize();
            yawCompensation = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-stabilized.x, stabilized.z)
                    - Math.atan2(-current.x, current.z)));
            pitchCompensation = Math.toDegrees(Math.asin(Mth.clamp(stabilized.y, -1, 1))
                    - Math.asin(Mth.clamp(current.y, -1, 1)));
        }
        Direction initial = cannon.initialOrientation();
        float sign = (initial.getAxisDirection() == Direction.AxisDirection.POSITIVE) == (initial.getAxis() == Direction.Axis.X) ? 1 : -1;
        float oldYaw = entity.yaw;
        float oldPitch = entity.pitch;
        float nextYaw = entity.yaw + (float) com.createdtr.defendtherealm.combat.TurretStabilization.step(
                yawCompensation, Mth.wrapDegrees(yawError - yawCompensation));
        float nextPitch = Mth.clamp(entity.pitch + (float) com.createdtr.defendtherealm.combat.TurretStabilization.step(
                pitchCompensation, pitchError - pitchCompensation),
                -entity.maximumDepression(), entity.maximumElevation());
        mount.setYaw(nextYaw);
        mount.setPitch(nextPitch * sign);
        // Sable does not reliably tick CBC's nested mount bridge. Invoke CBC's
        // own mount-to-contraption rotation path immediately. Restored mounts
        // can reject their controller identity, so preserve the verified Sable
        // fallback by assigning the nested controls to the same bounded pose.
        ((com.createdtr.defendtherealm.mixin.CannonMountRotationInvoker) mount).dtr$applyRotation();
        entity.yaw = nextYaw;
        entity.pitch = nextPitch;
        mount.setChanged();
        if (Math.abs(nextYaw - oldYaw) > 1.0e-4F || Math.abs(nextPitch - oldPitch) > 1.0e-4F) {
            mount.sendData();
            context.putLong("lastMountSyncTick", ship.getLevel().getGameTime());
        }
        // CBC fires from partial tick zero. Require its firing pose and its
        // current render pose to agree with the recomputed solution.
        BarrelPose firingPose = barrelPose(ship, entity, cannon, 0);
        BarrelPose renderPose = barrelPose(ship, entity, cannon, 1);
        solution = BallisticFireControl.solve(firingPose.projectileSpawn(), target, targetVelocity, trajectory);
        if (!solution.reachable()) return;
        Vec3 desiredWorld = solution.launchDirection();
        double firingError = angleDegrees(firingPose.direction(), desiredWorld);
        double renderError = angleDegrees(renderPose.direction(), desiredWorld);
        double error = Math.max(firingError, renderError);
        context.putDouble("aimError", error);
        context.putDouble("firingAimError", firingError);
        context.putDouble("renderAimError", renderError);
        long now = ship.getLevel().getGameTime();
        context.putDouble("muzzleX", firingPose.projectileSpawn().x);
        context.putDouble("muzzleY", firingPose.projectileSpawn().y);
        context.putDouble("muzzleZ", firingPose.projectileSpawn().z);
        context.putDouble("desiredBarrelX", solution.launchDirection().x);
        context.putDouble("desiredBarrelY", solution.launchDirection().y);
        context.putDouble("desiredBarrelZ", solution.launchDirection().z);
        context.putDouble("actualBarrelX", renderPose.direction().x);
        context.putDouble("actualBarrelY", renderPose.direction().y);
        context.putDouble("actualBarrelZ", renderPose.direction().z);
        context.putDouble("firingBarrelX", firingPose.direction().x);
        context.putDouble("firingBarrelY", firingPose.direction().y);
        context.putDouble("firingBarrelZ", firingPose.direction().z);
        context.putDouble("predictedImpactX", solution.predictedImpact().x);
        context.putDouble("predictedImpactY", solution.predictedImpact().y);
        context.putDouble("predictedImpactZ", solution.predictedImpact().z);
        context.putDouble("predictedFlightTicks", solution.flightTicks());
        boolean clear = clearShot(ship, mount, solution, acceptedHit, context);
        context.putBoolean("clearShot", clear);
        boolean externalBlocker = context.contains("shotBlockedAt") && !context.getBoolean("shotBlockedSelf")
                && !"unloaded".equals(context.getString("shotBlockedBy"));
        context.putBoolean("breachShotAuthorized", allowWorldObstruction && externalBlocker);
        if (engage && error <= 1 && (clear || allowWorldObstruction && externalBlocker)
                && ship.latestLinearVelocity.length() <= maximumFiringSpeed
                && now >= context.getLong("nextShot")) {
            context.putLong("nextShot", now + com.createdtr.defendtherealm.Config.SHOT_INTERVAL.get());
            context.putLong("permittedShotTick", now);
            context.putUUID("permittedCannonEntity", entity.getUUID());
            context.putString("permittedWeaponId", weaponId);
            if (targetPlayer != null) context.putUUID("permittedTargetPlayer", targetPlayer);
            else context.remove("permittedTargetPlayer");
            if (acceptedHit != null) context.putLong("permittedTargetBlock", acceptedHit.asLong());
            else context.remove("permittedTargetBlock");
            context.putInt("fireRequests", context.getInt("fireRequests") + 1);
            context.putLong("expectedProjectileTick", now);
            context.putDouble("expectedProjectileSpawnX", firingPose.projectileSpawn().x);
            context.putDouble("expectedProjectileSpawnY", firingPose.projectileSpawn().y);
            context.putDouble("expectedProjectileSpawnZ", firingPose.projectileSpawn().z);
            context.putDouble("expectedProjectileDirectionX", firingPose.direction().x);
            context.putDouble("expectedProjectileDirectionY", firingPose.direction().y);
            context.putDouble("expectedProjectileDirectionZ", firingPose.direction().z);
            // Sable restores the nested CBC contraption but does not reliably
            // tick its vanilla mount redstone bridge. Invoke CBC's public shot
            // operation directly; CBC still performs its real ammunition,
            // breech, projectile and failure checks.
            EncounterSavedData data = EncounterSavedData.get(ship.getLevel().getServer());
            data.updateContext(context);
            boolean assembled = mount.getBlockState().getValue(CannonMountBlock.ASSEMBLY_POWERED);
            mount.onRedstoneUpdate(assembled, assembled, true, false, 15);
            cannon.fireShot(ship.getLevel(), entity);
            CompoundTag accounted = data.context();
            context.putInt("shots", accounted.getInt("shots"));
            context.putInt("replenishments", accounted.getInt("replenishments"));
            context.putInt("spentItemChecks", accounted.getInt("spentItemChecks"));
            context.putInt("suppressedCasings", accounted.getInt("suppressedCasings"));
            if (accounted.contains("lastSuppressedCasing"))
                context.putString("lastSuppressedCasing", accounted.getString("lastSuppressedCasing"));
            context.putLong("permittedShotTick", accounted.getLong("permittedShotTick"));
            if (accounted.contains("replenishmentFailure"))
                context.putString("replenishmentFailure", accounted.getString("replenishmentFailure"));
            context.putLong("ammunitionBeforeShot", accounted.getLong("ammunitionBeforeShot"));
            context.putLong("ammunitionAfterShot", accounted.getLong("ammunitionAfterShot"));
            context.putLong("observedAmmunitionDelta", accounted.getLong("observedAmmunitionDelta"));
            for (String key : List.of("observedProjectileSpeed", "observedProjectileVelocityX",
                    "observedProjectileVelocityY", "observedProjectileVelocityZ", "observedProjectileSpawnX",
                    "observedProjectileSpawnY", "observedProjectileSpawnZ", "projectileSpawnError",
                    "projectileDirectionError"))
                if (accounted.contains(key)) context.putDouble(key, accounted.getDouble(key));
            for (String key : List.of("observedProjectileTick", "projectilePoseTickDelta"))
                if (accounted.contains(key)) context.putLong(key, accounted.getLong(key));
            if (accounted.contains("projectilePoseSameTick"))
                context.putBoolean("projectilePoseSameTick", accounted.getBoolean("projectilePoseSameTick"));
        }
    }
    private static double angleDegrees(Vec3 first, Vec3 second) {
        return Math.toDegrees(Math.acos(Mth.clamp(first.normalize().dot(second.normalize()), -1, 1)));
    }
    public static boolean assessClearShot(ServerSubLevel ship, Vec3 target, BlockPos acceptedHit, CompoundTag context) {
        return assessClearShot(ship, find(ship), target, acceptedHit, context);
    }
    public static boolean assessClearShot(ServerSubLevel ship, CannonMountBlockEntity mount, Vec3 target,
            BlockPos acceptedHit, CompoundTag context) {
        if (mount == null || mount.getContraption() == null) return false;
        return clearShot(ship, mount, muzzle(ship, mount), target, acceptedHit, context);
    }
    public static boolean assessClearShot(ServerSubLevel ship, CannonMountBlockEntity mount, Vec3 target,
            BlockPos acceptedHit, CompoundTag context, TrajectoryProfile profile) {
        if (mount == null || mount.getContraption() == null) return false;
        Vec3 muzzle = muzzle(ship, mount);
        FireSolution solution = BallisticFireControl.solve(muzzle, target, Vec3.ZERO, profile);
        return solution.reachable() && clearShot(ship, mount, solution, acceptedHit, context);
    }
    private static boolean clearShot(ServerSubLevel ship, CannonMountBlockEntity mount, Vec3 muzzle, Vec3 target,
            BlockPos acceptedHit, CompoundTag context) {
        return clearShot(ship, mount, new FireSolution(true, target.subtract(muzzle).normalize(), target,
                muzzle.distanceTo(target), List.of(muzzle, target), "legacy_direct"), acceptedHit, context);
    }
    private static boolean clearShot(ServerSubLevel ship, CannonMountBlockEntity mount, FireSolution solution,
            BlockPos acceptedHit, CompoundTag context) {
        var level = ship.getLevel();
        var shipBlocks = ship.getPlot().getEmbeddedLevelAccessor();
        context.remove("shotBlockedBy");
        context.remove("shotBlockedAt");
        context.remove("shotBlockedSelf");
        List<Vec3> path = solution.path();
        for (int i = 1; i < path.size(); i++) {
            Vec3 p = path.get(i);
            BlockPos world = BlockPos.containing(p);
            if (!level.hasChunkAt(world)) {
                context.putString("shotBlockedBy", "unloaded");
                context.putLong("shotBlockedAt", world.asLong());
                return false;
            }
            BlockPos local = BlockPos.containing(ship.logicalPose().transformPositionInverse(p));
            // The inverse pose produces sub-level-local coordinates. Query them
            // through Sable's embedded accessor, not the parent world's blocks.
            var state = shipBlocks.getBlockState(local);
            if (!state.getCollisionShape(shipBlocks, local).isEmpty()) {
                context.putString("shotBlockedBy", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                context.putLong("shotBlockedAt", local.asLong());
                context.putBoolean("shotBlockedSelf", true);
                return false;
            }
        }
        for (int i = 1; i < path.size(); i++) {
            var hit = level.clip(new ClipContext(path.get(i - 1), path.get(i), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, mount == null ? null : mount.getContraption()));
            if (hit.getType() == HitResult.Type.MISS) continue;
            if (acceptedHit != null && hit.getBlockPos().equals(acceptedHit)) return true;
            context.putString("shotBlockedBy", BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            context.putLong("shotBlockedAt", hit.getBlockPos().asLong());
            context.putBoolean("shotBlockedSelf", false);
            return false;
        }
        return true;
    }
}
