package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.combat.WeaponRangeProfile;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import com.createdtr.defendtherealm.hq.DevTargetSavedData;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative prototype target priority and persisted fallback list. */
public final class AssaultTargeting {
    public enum Type { PLAYER, DEFENSE, INFRASTRUCTURE, HQ }
    public record Target(Type type, String key, Vec3 aim, Vec3 velocity, BlockPos block) {}
    private AssaultTargeting() {}

    public static Target resolveOrSelect(ServerLevel level, Vec3 ship, BlockPos hq, CompoundTag context) {
        return resolveOrSelect(level, ship, ship, hq, context,
                new WeaponRangeProfile(0, 0, 48, 48, 0.35), false);
    }

    public static Target resolveOrSelect(ServerLevel level, Vec3 ship, Vec3 muzzle, BlockPos hq,
            CompoundTag context, WeaponRangeProfile range, boolean hqEngageable) {
        Target current = resolve(level, hq, context);
        if (current != null && !range.contains(muzzle.distanceTo(current.aim()))) current = null;
        long now = level.getGameTime();
        if (current != null && now < context.getLong("nextTargetReview")) return current;
        context.putLong("nextTargetReview", now + 10);
        Target selected = select(level, ship, muzzle, hq, context, range, hqEngageable);
        if (selected != null && (current == null || !current.key().equals(selected.key()))) {
            write(context, selected);
            context.putString("tacticalPhase", "APPROACH");
            context.putInt("orbitAttempts", 0);
            context.putInt("breachShots", 0);
            context.putInt("opportunityShots", 0);
            context.remove("maneuverRoute");
            context.remove("maneuverCursor");
            context.remove("maneuverDestination");
            context.remove("maneuverProgressDistance");
            context.remove("maneuverLastProgress");
            context.remove("orbitSearchStartedTick");
            context.remove("maneuverHoldX");
            context.remove("maneuverHoldY");
            context.remove("maneuverHoldZ");
        }
        return selected;
    }

    public static void rejectCurrent(CompoundTag context) {
        String key = context.getString("combatTargetKey");
        if (!key.isEmpty()) {
            ListTag list = context.getList("rejectedTargets", Tag.TAG_STRING);
            if (list.stream().noneMatch(tag -> tag.getAsString().equals(key))) list.add(StringTag.valueOf(key));
            context.put("rejectedTargets", list);
        }
        context.remove("combatTargetType");
        context.remove("combatTargetKey");
        context.remove("combatTargetPos");
        context.remove("combatTargetPlayer");
        context.putString("tacticalPhase", "APPROACH");
    }

    private static Target select(ServerLevel level, Vec3 ship, Vec3 muzzle, BlockPos hq, CompoundTag context,
            WeaponRangeProfile range, boolean hqEngageable) {
        Set<String> rejected = rejected(context);
        String hqKey = "hq:" + hq.asLong();
        // The HQ remains the vehicle's strategic destination, but an independent
        // weapon should take an eligible player opportunity along that route.
        // Selecting the HQ first here made a long-range turret ignore a player
        // as soon as both targets entered the same range envelope.
        Optional<ServerPlayer> player = level.players().stream()
                .filter(p -> !p.isCreative() && !p.isSpectator() && p.isAlive())
                .filter(p -> qualifies(worldPosition(p), ship, Vec3.atCenterOf(hq)))
                .filter(p -> range.contains(worldPosition(p).distanceTo(muzzle)))
                .filter(p -> !rejected.contains("player:" + p.getUUID()))
                .min(Comparator.comparingDouble(p -> worldPosition(p).distanceToSqr(muzzle)));
        if (player.isPresent()) return player(player.get());
        if (hqEngageable && !rejected.contains(hqKey))
            return new Target(Type.HQ, hqKey, Vec3.atCenterOf(hq), Vec3.ZERO, hq);
        if (!rejected.contains(hqKey)) return new Target(Type.HQ, hqKey, Vec3.atCenterOf(hq), Vec3.ZERO, hq);
        for (BlockPos pos : DevTargetSavedData.get(level).loaded(level, DevTargetSavedData.Kind.DEFENSE, hq).stream()
                .sorted(Comparator.comparingDouble(p -> p.distToCenterSqr(ship))).toList()) {
            String key = "defense:" + pos.asLong();
            if (!rejected.contains(key) && range.contains(Vec3.atCenterOf(pos).distanceTo(muzzle)))
                return new Target(Type.DEFENSE, key, Vec3.atCenterOf(pos), Vec3.ZERO, pos);
        }
        for (BlockPos pos : DevTargetSavedData.get(level).loaded(level, DevTargetSavedData.Kind.INFRASTRUCTURE, hq).stream()
                .sorted(Comparator.comparingDouble(p -> p.distToCenterSqr(ship))).toList()) {
            String key = "infrastructure:" + pos.asLong();
            if (!rejected.contains(key) && range.contains(Vec3.atCenterOf(pos).distanceTo(muzzle)))
                return new Target(Type.INFRASTRUCTURE, key, Vec3.atCenterOf(pos), Vec3.ZERO, pos);
        }
        return null;
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
    private static Set<String> rejected(CompoundTag context) {
        Set<String> result = new HashSet<>();
        for (Tag tag : context.getList("rejectedTargets", Tag.TAG_STRING)) result.add(tag.getAsString());
        return result;
    }
    static boolean qualifies(Vec3 player, Vec3 ship, Vec3 hq) {
        if (player.distanceToSqr(hq) <= 128D * 128D) return true;
        Vec3 segment = hq.subtract(ship);
        double length = segment.lengthSqr();
        double t = length == 0 ? 0 : Math.clamp(player.subtract(ship).dot(segment) / length, 0, 1);
        return player.distanceToSqr(ship.add(segment.scale(t))) <= 48D * 48D;
    }
}
