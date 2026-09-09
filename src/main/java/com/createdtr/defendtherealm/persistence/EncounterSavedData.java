package com.createdtr.defendtherealm.persistence;

import com.createdtr.defendtherealm.encounter.Encounter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** One world-wide slot, including the last completed record to prevent resurrection. */
public final class EncounterSavedData extends SavedData {
    private static final int SCHEMA = 2;
    private Encounter encounter;
    private CompoundTag context = new CompoundTag();

    public static EncounterSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(EncounterSavedData::new, EncounterSavedData::load), "createdefendtherealm_encounter");
    }

    public Encounter encounter() { return encounter; }
    public CompoundTag context() { return context.copy(); }

    public void begin(Encounter next, CompoundTag nextContext) {
        if (encounter != null && encounter.state() != Encounter.State.COMPLETED) {
            throw new IllegalStateException("An encounter is already active");
        }
        if (next.state() != Encounter.State.VALIDATING) throw new IllegalArgumentException("Expected new encounter");
        encounter = next;
        context = nextContext.copy();
        setDirty();
    }

    public void updateContext(CompoundTag value) { context = value.copy(); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SCHEMA);
        if (encounter == null) return tag;
        tag.putUUID("id", encounter.id());
        tag.putString("state", encounter.state().name());
        tag.putString("reason", encounter.reason().name());
        tag.put("context", context.copy());
        ListTag weights = new ListTag();
        encounter.weights().forEach((pos, weight) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLong("position", pos);
            entry.putInt("weight", weight);
            weights.add(entry);
        });
        tag.put("weights", weights);
        tag.putLongArray("lost", encounter.lost().stream().mapToLong(Long::longValue).toArray());
        ListTag owned = new ListTag();
        encounter.owned().forEach(id -> { CompoundTag entry = new CompoundTag(); entry.putUUID("id", id); owned.add(entry); });
        tag.put("owned", owned);
        return tag;
    }

    public static EncounterSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("schema") < 1 || tag.getInt("schema") > SCHEMA) throw new IllegalArgumentException("Unsupported DTR encounter schema");
        EncounterSavedData data = new EncounterSavedData();
        if (!tag.contains("id")) return data;
        var weights = new HashMap<Long, Integer>();
        for (Tag value : tag.getList("weights", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (weights.put(entry.getLong("position"), entry.getInt("weight")) != null) {
                throw new IllegalArgumentException("Duplicate integrity position");
            }
        }
        var lost = new HashSet<Long>();
        for (long pos : tag.getLongArray("lost")) lost.add(pos);
        var owned = new HashSet<UUID>();
        for (Tag value : tag.getList("owned", Tag.TAG_COMPOUND)) owned.add(((CompoundTag) value).getUUID("id"));
        data.encounter = Encounter.restore(tag.getUUID("id"), weights, lost, owned,
                Encounter.State.valueOf(tag.getString("state")), Encounter.Reason.valueOf(tag.getString("reason")));
        data.context = tag.getCompound("context").copy();
        return data;
    }
}
