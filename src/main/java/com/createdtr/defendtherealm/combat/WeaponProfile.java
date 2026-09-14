package com.createdtr.defendtherealm.combat;

import net.minecraft.core.BlockPos;

public record WeaponProfile(String id, WeaponBehavior behavior, WeaponRangeProfile range,
        TrajectoryProfile trajectory, boolean breachCapable, int breachShotLimit, BlockPos mountOffset) {
    public WeaponProfile {
        if (id == null || !id.matches("[a-z0-9_]{1,48}")) throw new IllegalArgumentException("Invalid weapon id");
        if (behavior == null || range == null || trajectory == null || breachShotLimit < 0)
            throw new IllegalArgumentException("Invalid weapon profile");
        if (breachCapable && breachShotLimit == 0)
            throw new IllegalArgumentException("Breaching weapons need a per-blocker shot budget");
    }
}
