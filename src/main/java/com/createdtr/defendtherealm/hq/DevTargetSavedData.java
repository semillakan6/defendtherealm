package com.createdtr.defendtherealm.hq;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Positions of developer-only defense and infrastructure targets. */
public final class DevTargetSavedData extends SavedData {
    public enum Kind { DEFENSE, INFRASTRUCTURE }
    private final EnumMap<Kind, Set<Long>> positions = new EnumMap<>(Kind.class);

    public DevTargetSavedData() { for (Kind kind : Kind.values()) positions.put(kind, new HashSet<>()); }
    public static DevTargetSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(DevTargetSavedData::new, DevTargetSavedData::load),
                "createdefendtherealm_dev_targets");
    }
    public void add(Kind kind, BlockPos pos) { if (positions.get(kind).add(pos.asLong())) setDirty(); }
    public void remove(Kind kind, BlockPos pos) { if (positions.get(kind).remove(pos.asLong())) setDirty(); }
    public List<BlockPos> loaded(ServerLevel level, Kind kind, BlockPos hq) {
        var block = kind == Kind.DEFENSE ? CreateDefendtheRealm.DEV_DEFENSE.get() : CreateDefendtheRealm.DEV_INFRASTRUCTURE.get();
        if (positions.get(kind).removeIf(p -> level.hasChunkAt(BlockPos.of(p)) && !level.getBlockState(BlockPos.of(p)).is(block))) setDirty();
        return positions.get(kind).stream().map(BlockPos::of).filter(level::hasChunkAt)
                .filter(p -> p.distSqr(hq) <= 512D * 512D)
                .sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (Kind kind : Kind.values()) tag.putLongArray(kind.name().toLowerCase(), positions.get(kind).stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
    private static DevTargetSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        DevTargetSavedData data = new DevTargetSavedData();
        for (Kind kind : Kind.values()) for (long pos : tag.getLongArray(kind.name().toLowerCase())) data.positions.get(kind).add(pos);
        return data;
    }
}
