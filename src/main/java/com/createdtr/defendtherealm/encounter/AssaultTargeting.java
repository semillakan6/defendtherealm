package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.combat.WeaponRangeProfile;
import com.createdtr.defendtherealm.hq.DevTargetSavedData;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative prototype target priority and persisted fallback list. */
public final class AssaultTargeting {
    public enum Type { PLAYER, DEFENSE, INFRASTRUCTURE, HQ }
    public record Target(Type type, String key, Vec3 aim, Vec3 velocity, BlockPos block) {}
    private AssaultTargeting() {}

    static int priority(Type type) {
        return switch (type) {
            case HQ -> 0;
            case PLAYER -> 1;
            case DEFENSE -> 2;
            case INFRASTRUCTURE -> 3;
        };
    }

    public static Target resolveOrSelect(ServerLevel level, Vec3 ship, BlockPos hq, CompoundTag context) {
        return resolveOrSelect(level, ship, ship, hq, context,
                new WeaponRangeProfile(0, 0, 48, 48, 0.35), false);
    }

    public static Target resolveOrSelect(ServerLevel level, Vec3 ship, Vec3 muzzle, BlockPos hq,
            CompoundTag context, WeaponRangeProfile range, boolean hqEngageable) {
        return resolveOrSelect(level, ship, muzzle, hq, context, range, hqEngageable, target -> true);
    }

    public static Target resolveOrSelect(ServerLevel level, Vec3 ship, Vec3 muzzle, BlockPos hq,
            CompoundTag context, WeaponRangeProfile range, boolean hqEngageable, Predicate<Target> feasible) {
        Target current = resolve(level, hq, context);
        if (current != null && (!range.contains(muzzle.distanceTo(current.aim()))
                || current.type() != Type.HQ && !feasible.test(current))) current = null;
        long now = level.getGameTime();
        if (current != null && now < context.getLong("nextTargetReview")
                && !(hqEngageable && current.type() != Type.HQ)) return current;
        context.putLong("nextTargetReview", now + 10);
        Target selected = select(level, muzzle, hq, range, hqEngageable, feasible);
        if (selected != null && (current == null || !current.key().equals(selected.key()))) {
            write(context, selected);
            context.putString("tacticalPhase", "APPROACH");
            context.putLong("targetSelectedTick", now);
        }
        return selected;
    }

    public static void rejectCurrent(CompoundTag context) {
        context.remove("combatTargetType");
        context.remove("combatTargetKey");
        context.remove("combatTargetPos");
        context.remove("combatTargetPlayer");
        context.putString("tacticalPhase", "APPROACH");
    }

    private static Target select(ServerLevel level, Vec3 muzzle, BlockPos hq,
            WeaponRangeProfile range, boolean hqEngageable, Predicate<Target> feasible) {
        String hqKey = "hq:" + hq.asLong();
        // The HQ has top weapon priority whenever it is currently engageable.
        // An independent turret may take a player opportunity while the HQ is
        // outside range or blocked, without replacing the strategic HQ route.
        if (hqEngageable)
            return new Target(Type.HQ, hqKey, Vec3.atCenterOf(hq), Vec3.ZERO, hq);
        Optional<ServerPlayer> player = level.players().stream()
                .filter(p -> !p.isCreative() && !p.isSpectator() && p.isAlive())
                .filter(p -> range.contains(worldPosition(p).distanceTo(muzzle)))
                .filter(p -> feasible.test(player(p)))
                .min(Comparator.comparingDouble(p -> worldPosition(p).distanceToSqr(muzzle)));
        if (player.isPresent()) return player(player.get());
        Target defense = nearestMarker(level, muzzle, hq, DevTargetSavedData.Kind.DEFENSE,
                Type.DEFENSE, range, feasible);
        if (defense != null) return defense;
        Target infrastructure = nearestMarker(level, muzzle, hq, DevTargetSavedData.Kind.INFRASTRUCTURE,
                Type.INFRASTRUCTURE, range, feasible);
        if (infrastructure != null) return infrastructure;
        return new Target(Type.HQ, hqKey, Vec3.atCenterOf(hq), Vec3.ZERO, hq);
    }

    private static Target nearestMarker(ServerLevel level, Vec3 muzzle, BlockPos hq,
            DevTargetSavedData.Kind kind, Type type, WeaponRangeProfile range, Predicate<Target> feasible) {
        return DevTargetSavedData.get(level).loaded(level, kind, hq).stream()
                .map(pos -> new Target(type, type.name().toLowerCase() + ":" + pos.asLong(),
                        Vec3.atCenterOf(pos), Vec3.ZERO, pos))
                .filter(target -> range.contains(muzzle.distanceTo(target.aim())))
                .filter(feasible)
                .min(Comparator.comparingDouble(target -> target.aim().distanceToSqr(muzzle)))
                .orElse(null);
    }

    private static Target resolve(ServerLevel level, BlockPos hq, CompoundTag context) {
        if (!context.contains("combatTargetType")) return null;
        Type type;
        try { type = Type.valueOf(context.getString("combatTargetType")); }
        catch (IllegalArgumentException ex) { return null; }
        if (type == Type.PLAYER) {
            if (!context.hasUUID("combatTargetPlayer")) return null;
            var entity = level.getEntity(context.getUUID("combatTargetPlayer"));
            ServerPlayer player = entity instanceof ServerPlayer value ? value : null;
            return player != null && player.isAlive() && !player.isCreative() && !player.isSpectator()
                    ? player(player) : null;
        }
        BlockPos pos = type == Type.HQ ? hq : BlockPos.of(context.getLong("combatTargetPos"));
        boolean valid = switch (type) {
            case HQ -> level.getBlockState(pos).is(CreateDefendtheRealm.DEV_HQ.get());
            case DEFENSE -> level.getBlockState(pos).is(CreateDefendtheRealm.DEV_DEFENSE.get());
            case INFRASTRUCTURE -> level.getBlockState(pos).is(CreateDefendtheRealm.DEV_INFRASTRUCTURE.get());
            default -> false;
        };
        return valid ? new Target(type, context.getString("combatTargetKey"), Vec3.atCenterOf(pos), Vec3.ZERO, pos) : null;
    }

    private static Target player(ServerPlayer player) {
        Vec3 feet = worldPosition(player);
        Vec3 centerMass = feet.add(0, player.getBbHeight() * 0.55D, 0);
        return new Target(Type.PLAYER, "player:" + player.getUUID(), centerMass, worldVelocity(player), BlockPos.containing(centerMass));
    }

    public static Vec3 worldPosition(ServerPlayer player) {
        SubLevel tracking = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        return tracking == null ? player.position() : tracking.logicalPose().transformPosition(player.position());
    }

    private static Vec3 worldVelocity(ServerPlayer player) {
        SubLevel tracking = Sable.HELPER.getTrackingOrVehicleSubLevel(player);
        Vec3 velocity = player.getDeltaMovement();
        return tracking == null ? velocity : tracking.logicalPose().transformNormal(velocity);
    }
    private static void write(CompoundTag context, Target target) {
        context.putString("combatTargetType", target.type().name());
        context.putString("combatTargetKey", target.key());
        if (target.type() == Type.PLAYER) context.putUUID("combatTargetPlayer", UUID.fromString(target.key().substring(7)));
        else context.putLong("combatTargetPos", target.block().asLong());
    }
}
