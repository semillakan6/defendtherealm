package com.createdtr.defendtherealm.combat;

public record WeaponRangeProfile(double minimum, double preferredMinimum, double preferredMaximum,
        double maximum, double maximumFiringSpeed) {
    public WeaponRangeProfile {
        if (!Double.isFinite(minimum + preferredMinimum + preferredMaximum + maximum + maximumFiringSpeed)
                || minimum < 0 || preferredMinimum < minimum || preferredMaximum < preferredMinimum
                || maximum < preferredMaximum || maximumFiringSpeed < 0) {
            throw new IllegalArgumentException("Invalid weapon range profile");
        }
    }

    public boolean contains(double distance) { return distance >= minimum && distance <= maximum; }
    public boolean preferred(double distance) { return distance >= preferredMinimum && distance <= preferredMaximum; }
    public double preferredRadius() { return preferredMaximum; }
}
