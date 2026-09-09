package com.createdtr.defendtherealm.integration.cbc;

import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
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
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;

/** Compiled CBC integration; setters are rate limited and firing remains in CBC's tick. */
public final class PrototypeWeapon {
    private PrototypeWeapon() {}
    public static CannonMountBlockEntity find(ServerSubLevel ship) {
        var bounds = ship.getPlot().getBoundingBox();
        for (BlockPos pos : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()))
            if (ship.getLevel().getBlockEntity(pos) instanceof CannonMountBlockEntity mount) return mount;
        return null;
    }
    public static void stop(CannonMountBlockEntity mount) {
        if (mount == null || mount.isRemoved()) return;
        boolean assembled = mount.getBlockState().getValue(CannonMountBlock.ASSEMBLY_POWERED);
        mount.onRedstoneUpdate(assembled, assembled, false, mount.getBlockState().getValue(CannonMountBlock.FIRE_POWERED), 0);
    }
    public static Vec3 muzzle(ServerSubLevel ship, CannonMountBlockEntity mount) {
        var entity = mount.getContraption();
        if (entity == null || !(entity.getContraption() instanceof MountedAutocannonContraption cannon))
            throw new IllegalStateException("Missing mounted autocannon");
        Direction direction = cannon.initialOrientation();
        BlockPos end = cannon.getStartPos();
        for (int i = 0; i < 128 && cannon.getBlocks().containsKey(end.relative(direction)); i++) end = end.relative(direction);
        return ship.logicalPose().transformPosition(entity.toGlobalVector(Vec3.atCenterOf(end).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.6)), 1));
    }
    public static void tick(ServerSubLevel ship, BlockPos target, CompoundTag context, boolean engage) {
        CannonMountBlockEntity mount = find(ship);
        if (mount == null || mount.getContraption() == null) throw new IllegalStateException("Autocannon mount lost");
        stop(mount);
        var entity = mount.getContraption();
        if (!(entity.getContraption() instanceof MountedAutocannonContraption cannon)) throw new IllegalStateException("Unsupported cannon");
        // Advance the restored nested contraption once per server tick so its
        // breech cooldown and internal block entities remain live under Sable.
        cannon.tick(ship.getLevel(), entity);
        ShotAccounting.flush(ship.getLevel(), cannon, context);
        context.putUUID("cannonEntity", entity.getUUID());
        context.putBoolean("mountRunning", mount.isRunning());
        Vec3 muzzle = muzzle(ship, mount);
        Vec3 desired = Vec3.atCenterOf(target).subtract(muzzle);
        Vec3 local = ship.logicalPose().transformNormalInverse(desired.normalize());
        Vec3 current = entity.applyRotation(Vec3.atLowerCornerOf(cannon.initialOrientation().getNormal()), 1).normalize();
        double yawError = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-local.x, local.z) - Math.atan2(-current.x, current.z)));
        double pitchError = Math.toDegrees(Math.asin(Mth.clamp(local.y, -1, 1)) - Math.asin(Mth.clamp(current.y, -1, 1)));
        Direction initial = cannon.initialOrientation();
        float sign = (initial.getAxisDirection() == Direction.AxisDirection.POSITIVE) == (initial.getAxis() == Direction.Axis.X) ? 1 : -1;
        float nextYaw = entity.yaw + (float) Mth.clamp(yawError, -0.75, 0.75);
        float nextPitch = Mth.clamp(entity.pitch + (float) Mth.clamp(pitchError, -0.75, 0.75),
                -entity.maximumDepression(), entity.maximumElevation());
        mount.setYaw(nextYaw);
        mount.setPitch(nextPitch * sign);
        // CBC's mount tick normally copies these fields. Sable's nested runtime
        // restoration does not tick that copy reliably, so synchronize the public
        // contraption controls through the same bounded values.
        entity.yaw = nextYaw;
        entity.pitch = nextPitch;
        mount.setChanged();
        double error = Math.toDegrees(Math.acos(Mth.clamp(current.dot(local), -1, 1)));
        context.putDouble("aimError", error);
        long now = ship.getLevel().getGameTime();
        context.putDouble("muzzleX", muzzle.x);
        context.putDouble("muzzleY", muzzle.y);
        context.putDouble("muzzleZ", muzzle.z);
        boolean clear = clearShot(ship, muzzle, Vec3.atCenterOf(target), target, context);
        context.putBoolean("clearShot", clear);
        if (engage && desired.length() <= 48 && error <= 1 && clear && ship.latestLinearVelocity.length() < 0.35
                && now >= context.getLong("nextShot")) {
            context.putLong("nextShot", now + com.createdtr.defendtherealm.Config.SHOT_INTERVAL.get());
            context.putLong("permittedShotTick", now);
            context.putInt("fireRequests", context.getInt("fireRequests") + 1);
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
            context.putLong("permittedShotTick", accounted.getLong("permittedShotTick"));
            if (accounted.contains("replenishmentFailure"))
                context.putString("replenishmentFailure", accounted.getString("replenishmentFailure"));
            context.putLong("ammunitionBeforeShot", accounted.getLong("ammunitionBeforeShot"));
            context.putLong("ammunitionAfterShot", accounted.getLong("ammunitionAfterShot"));
            context.putLong("observedAmmunitionDelta", accounted.getLong("observedAmmunitionDelta"));
        }
    }
    private static boolean clearShot(ServerSubLevel ship, Vec3 muzzle, Vec3 target, BlockPos hq, CompoundTag context) {
        var level = ship.getLevel();
        var shipBlocks = ship.getPlot().getEmbeddedLevelAccessor();
        context.remove("shotBlockedBy");
        context.remove("shotBlockedAt");
        int steps = (int) Math.ceil(muzzle.distanceTo(target) * 4);
        for (int i = 1; i < steps; i++) {
            Vec3 p = muzzle.lerp(target, (double) i / steps);
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
                return false;
            }
        }
        var hit = level.clip(new ClipContext(muzzle, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mountEntity(ship)));
        boolean clear = hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(hq);
        if (!clear) {
            context.putString("shotBlockedBy", BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            context.putLong("shotBlockedAt", hit.getBlockPos().asLong());
        }
        return clear;
    }
    private static net.minecraft.world.entity.Entity mountEntity(ServerSubLevel ship) {
        var mount = find(ship); return mount == null ? null : mount.getContraption();
    }
}
