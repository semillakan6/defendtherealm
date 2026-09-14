package com.createdtr.defendtherealm.combat;

public record TrajectoryProfile(TrajectoryType type, double projectileSpeed, double gravityPerTick,
        double dragPerTick, int maximumFlightTicks) {
    public TrajectoryProfile {
        if (type == null || !Double.isFinite(projectileSpeed + gravityPerTick + dragPerTick)
                || projectileSpeed <= 0 || gravityPerTick < 0 || dragPerTick < 0 || dragPerTick >= 1
                || maximumFlightTicks < 1 || maximumFlightTicks > 1200) {
            throw new IllegalArgumentException("Invalid trajectory profile");
        }
    }
}
