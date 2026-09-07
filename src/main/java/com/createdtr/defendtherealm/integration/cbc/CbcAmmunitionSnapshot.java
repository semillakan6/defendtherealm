package com.createdtr.defendtherealm.integration.cbc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** Reads only the finite cartridge count needed by the Milestone 01 blueprint gate. */
public final class CbcAmmunitionSnapshot {
    private static final String CARTRIDGE_ID = "createbigcannons:autocannon_cartridge";

    private CbcAmmunitionSnapshot() {}

    public static long finiteAutocannonCartridges(CompoundTag contraption) {
        if (contraption.getBoolean("BottomlessSupply")) {
            throw new IllegalArgumentException("Prototype cannon must use finite ammunition");
        }
        try {
            return countItem(contraption, CARTRIDGE_ID);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Prototype ammunition count overflow", exception);
        }
    }

    private static long countItem(Tag tag, String itemId) {
        if (tag instanceof CompoundTag compound) {
            long result = 0;
            if (itemId.equals(compound.getString("id"))) {
                Tag count = compound.get("count");
                if (!(count instanceof NumericTag number) || number.getAsLong() < 0) {
                    throw new IllegalArgumentException("Prototype contains an invalid ammunition stack");
                }
                result = number.getAsLong();
            }
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                if (child != null) result = Math.addExact(result, countItem(child, itemId));
            }
            return result;
        }
        if (tag instanceof ListTag list) {
            long result = 0;
            for (Tag child : list) result = Math.addExact(result, countItem(child, itemId));
            return result;
        }
        return 0;
    }
}
