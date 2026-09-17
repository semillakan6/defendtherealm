package com.createdtr.defendtherealm.combat;

import net.minecraft.core.BlockPos;

public record WeaponProfile(String id, WeaponBehavior behavior, WeaponRangeProfile range,
        TrajectoryProfile trajectory, boolean breachCapable, BlockPos mountOffset) {
    public WeaponProfile {
        if (id == null || !id.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid weapon id");
        if (behavior == null || range == null || trajectory == null)
            throw new IllegalArgumentException("Invalid weapon profile");
    }
}
