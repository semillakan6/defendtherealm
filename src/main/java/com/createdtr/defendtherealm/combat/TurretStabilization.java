package com.createdtr.defendtherealm.combat;

/** Bounded mount motion: compensate platform rotation, then track the target. */
public final class TurretStabilization {
    private TurretStabilization() {}

    public static double step(double compensation, double trackingError) {
        return Math.clamp(compensation + Math.clamp(trackingError, -0.75, 0.75), -3, 3);
    }
}
