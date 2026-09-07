package com.createdtr.defendtherealm.template;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Explicit field allowlist: never copy live coordinates, identity, networks or contraption references. */
public final class MachinerySnapshot {
    private static final Map<String, String[]> FIELDS = Map.of(
        "aeronautics:adjustable_burner", new String[] {"ScrollValue", "SignalStrength"},
        "simulated:throttle_lever", new String[] {"State"},
        "create:analog_lever", new String[] {"State"},
        "createbigcannons:cannon_mount", new String[] {}
    );

    private MachinerySnapshot() {}

    public static CompoundTag capture(String blockId, CompoundTag source) {
        String[] fields = FIELDS.get(blockId);
        if (fields == null) throw new IllegalArgumentException("Unsupported machinery: " + blockId);
        if (!source.getString("id").equals(blockId)) {
            throw new IllegalArgumentException("Unexpected block entity type for " + blockId);
        }
        if (blockId.equals("createbigcannons:cannon_mount") && source.getBoolean("Running")) {
            throw new IllegalArgumentException("Cannon is still assembled as an entity. Its mount alone is not a complete cannon template.");
        }
        CompoundTag result = new CompoundTag();
        result.putString("id", blockId);
        for (String field : fields) {
            if (!source.contains(field, Tag.TAG_INT)) throw new IllegalArgumentException("Missing integer field " + field + " on " + blockId);
            result.putInt(field, source.getInt(field));
        }
        return result;
    }
}
