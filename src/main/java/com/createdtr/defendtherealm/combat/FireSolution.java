package com.createdtr.defendtherealm.combat;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Immutable server-side firing solution; path points are in parent-world coordinates. */
public record FireSolution(boolean reachable, Vec3 launchDirection, Vec3 predictedImpact,
        double flightTicks, List<Vec3> path, String reason) {
    public FireSolution {
        path = List.copyOf(path);
    }

    public static FireSolution unreachable(String reason) {
        return new FireSolution(false, Vec3.ZERO, Vec3.ZERO, 0, List.of(), reason);
    }
}
