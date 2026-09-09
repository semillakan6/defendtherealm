package com.createdtr.defendtherealm.navigation;

import java.util.HashMap;
import java.util.Map;

public final class RoutePlanners {
    private static final Map<String, VehicleRoutePlanner> PLANNERS = new HashMap<>();
    static { register("hover_airship", new HoverAirshipPlanner()); }
    private RoutePlanners() {}
    public static synchronized void register(String profile, VehicleRoutePlanner planner) {
        if (PLANNERS.putIfAbsent(profile, java.util.Objects.requireNonNull(planner)) != null)
            throw new IllegalArgumentException("Duplicate navigation profile: " + profile);
    }
    public static VehicleRoutePlanner get(String profile) {
        VehicleRoutePlanner result = PLANNERS.get(profile);
        if (result == null) throw new IllegalArgumentException("Unsupported navigation profile: " + profile);
        return result;
    }
}
