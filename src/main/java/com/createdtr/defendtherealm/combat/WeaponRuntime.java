package com.createdtr.defendtherealm.combat;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Copy-safe per-hardpoint state stored inside the encounter context. */
public final class WeaponRuntime {
    private WeaponRuntime() {}

    public static CompoundTag state(CompoundTag context, String id) {
        ListTag states = context.getList("weaponStates", Tag.TAG_COMPOUND);
        for (Tag value : states) {
            CompoundTag state = (CompoundTag) value;
            if (id.equals(state.getString("id"))) return state;
        }
        CompoundTag created = new CompoundTag();
        created.putString("id", id);
        states.add(created);
        context.put("weaponStates", states);
        return created;
    }

    public static void initialize(CompoundTag context, VehicleCombatProfile profile) {
        context.putString("combatProfile", profile.id());
        context.putString("vehicleFamily", profile.family().name());
        context.putString("navigationProfile", profile.navigationProfile());
        for (WeaponProfile weapon : profile.weapons()) {
            CompoundTag state = state(context, weapon.id());
            if (!state.contains("nextShot") && context.contains("nextShot"))
                state.putLong("nextShot", context.getLong("nextShot"));
            state.putString("behavior", weapon.behavior().name());
            state.putDouble("minimumRange", weapon.range().minimum());
            state.putDouble("preferredMinimumRange", weapon.range().preferredMinimum());
            state.putDouble("preferredMaximumRange", weapon.range().preferredMaximum());
            state.putDouble("maximumRange", weapon.range().maximum());
            state.putDouble("maximumFiringSpeed", weapon.range().maximumFiringSpeed());
            state.putString("trajectoryType", weapon.trajectory().type().name());
            state.putDouble("configuredProjectileSpeed", weapon.trajectory().projectileSpeed());
            state.putDouble("gravityPerTick", weapon.trajectory().gravityPerTick());
            state.putDouble("dragPerTick", weapon.trajectory().dragPerTick());
        }
    }

    public static Map<Long, Integer> blockerBudgets(CompoundTag state) {
        Map<Long, Integer> result = new HashMap<>();
        for (Tag value : state.getList("blockerShots", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            result.put(entry.getLong("blocker"), entry.getInt("shots"));
        }
        return result;
    }

    public static int incrementBlocker(CompoundTag state, long blocker) {
        Map<Long, Integer> values = blockerBudgets(state);
        int shots = values.getOrDefault(blocker, 0) + 1;
        values.put(blocker, shots);
        ListTag list = new ListTag();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag tag = new CompoundTag();
            tag.putLong("blocker", entry.getKey()); tag.putInt("shots", entry.getValue()); list.add(tag);
        });
        state.put("blockerShots", list);
        return shots;
    }
}
