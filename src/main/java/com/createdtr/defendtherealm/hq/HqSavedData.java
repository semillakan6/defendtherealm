package com.createdtr.defendtherealm.hq;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class HqSavedData extends SavedData {
    private final Set<Long> positions = new HashSet<>();
    public static HqSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(HqSavedData::new, HqSavedData::load), "createdefendtherealm_hqs");
    }
    public void add(BlockPos pos) { if (positions.add(pos.asLong())) setDirty(); }
    public void remove(BlockPos pos) { if (positions.remove(pos.asLong())) setDirty(); }
    public BlockPos nearest(ServerLevel level, BlockPos origin) {
        if (positions.removeIf(p -> level.hasChunkAt(BlockPos.of(p))
                && !level.getBlockState(BlockPos.of(p)).is(CreateDefendtheRealm.DEV_HQ.get()))) setDirty();
        return positions.stream().map(BlockPos::of).filter(level::hasChunkAt)
                .filter(p -> p.distSqr(origin) <= 512D * 512D)
                .min(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(origin)).thenComparingLong(BlockPos::asLong))
                .orElseThrow(() -> new IllegalArgumentException("No loaded development HQ within 512 blocks in this dimension"));
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("positions", positions.stream().mapToLong(Long::longValue).toArray()); return tag;
    }
    private static HqSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        HqSavedData data = new HqSavedData();
        for (long pos : tag.getLongArray("positions")) data.positions.add(pos);
        return data;
    }
}
