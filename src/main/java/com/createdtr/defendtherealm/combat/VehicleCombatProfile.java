package com.createdtr.defendtherealm.combat;

import java.util.List;

public record VehicleCombatProfile(String id, String template, VehicleFamily family,
        String navigationProfile, List<WeaponProfile> weapons) {
    public VehicleCombatProfile {
        if (id == null || !id.matches("[a-z0-9_]{1,48}") || template == null || template.isBlank()
                || family == null || navigationProfile == null || navigationProfile.isBlank()
                || weapons == null || weapons.isEmpty()) throw new IllegalArgumentException("Invalid vehicle combat profile");
        weapons = List.copyOf(weapons);
        if (weapons.stream().map(WeaponProfile::id).distinct().count() != weapons.size())
            throw new IllegalArgumentException("Duplicate weapon id in " + id);
        if (weapons.size() > 1 && weapons.stream().anyMatch(weapon -> weapon.mountOffset() == null))
            throw new IllegalArgumentException("Multi-hardpoint profiles require explicit mount_offset values");
        if (family != VehicleFamily.HOVER_AIRSHIP || !"hover_airship".equals(navigationProfile))
            throw new IllegalArgumentException("Unsupported vehicle family/navigation profile: " + family + "/" + navigationProfile);
        if (weapons.stream().anyMatch(w -> w.behavior() != WeaponBehavior.INDEPENDENT_TURRET))
            throw new IllegalArgumentException("Unsupported weapon behavior in current milestone profile");
    }

    public WeaponProfile primaryWeapon() { return weapons.getFirst(); }
}
