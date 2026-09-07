package com.createdtr.defendtherealm.template;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/** Atomic capture with explicit machinery serializers and no entity cloning. */
public final class TemplateSavedData extends SavedData {
    private final Map<String, CompoundTag> templates = new LinkedHashMap<>();

    public static TemplateSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(TemplateSavedData::new, TemplateSavedData::load), "createdefendtherealm_templates");
    }

    public CompoundTag find(String name) {
        CompoundTag template = templates.get(name);
        return template == null ? null : template.copy();
    }

    public int capture(ServerLevel level, String name, BlockPos a, BlockPos b) {
        if (!name.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Use 1-48 lowercase letters, digits or underscores");
        if (templates.containsKey(name)) throw new IllegalArgumentException("Template already exists; choose a new revision name");
        if (templates.size() >= 16) throw new IllegalArgumentException("Prototype template limit (16) reached");
        if (!level.dimension().equals(Level.OVERWORLD)) throw new IllegalArgumentException("Prototype supports Overworld only");
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        long sx = (long) max.getX() - min.getX() + 1;
        long sy = (long) max.getY() - min.getY() + 1;
        long sz = (long) max.getZ() - min.getZ() + 1;
        if (sx > 32 || sy > 32 || sz > 32 || sx * sy * sz > 16384) {
            throw new IllegalArgumentException("Capture limited to 32 blocks per axis and 16384 cells");
        }
        ListTag blocks = new ListTag();
        boolean machinery = false;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) {
                throw new IllegalArgumentException("Capture must remain within loaded world bounds");
            }
            if (!level.getFluidState(pos).isEmpty()) throw new IllegalArgumentException("Fluid capture is not supported");
            if (!level.getBlockState(pos).isAir()) {
                CompoundTag block = new CompoundTag();
                block.putLong("position", pos.subtract(min).asLong());
                block.put("state", NbtUtils.writeBlockState(level.getBlockState(pos)));
                if (level.getBlockState(pos).hasBlockEntity()) {
                    var be = level.getBlockEntity(pos);
                    if (be == null) throw new IllegalArgumentException("Missing machinery at " + pos.toShortString());
                    block.put("data", MachinerySnapshot.capture(
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(),
                        be.saveWithFullMetadata(level.registryAccess())));
                    machinery = true;
                }
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("Capture contains no blocks");
        CompoundTag template = new CompoundTag();
        template.putInt("schema", 1);
        template.putString("name", name);
        template.putString("status", machinery ? "machinery_capture_unvalidated" : "structural_capture_only");
        template.putIntArray("size", new int[] {(int) sx, (int) sy, (int) sz});
        template.put("blocks", blocks);
        // Commit only after every cell passes validation. The source world is never modified.
        templates.put(name, template);
        setDirty();
        return blocks.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", 1);
        ListTag values = new ListTag();
        templates.values().forEach(value -> values.add(value.copy()));
        tag.put("templates", values);
        return tag;
    }

    public static TemplateSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported DTR template schema");
        TemplateSavedData result = new TemplateSavedData();
        for (Tag value : tag.getList("templates", Tag.TAG_COMPOUND)) {
            CompoundTag template = (CompoundTag) value;
            String name = template.getString("name");
            if (template.getInt("schema") != 1 || !name.matches("[a-z0-9_]{1,48}")
                    || result.templates.size() >= 16 || result.templates.putIfAbsent(name, template.copy()) != null) {
                throw new IllegalArgumentException("Invalid persisted DTR template");
            }
        }
        return result;
    }
}
