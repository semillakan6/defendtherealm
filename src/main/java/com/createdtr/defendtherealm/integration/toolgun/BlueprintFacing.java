package com.createdtr.defendtherealm.integration.toolgun;

import com.enxv.aeronauticsstructuretool.blueprint.placement.PlacementTargetMath;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Computes the Toolgun yaw that best aligns a saved blueprint with its target. */
public final class BlueprintFacing {
    private BlueprintFacing() {}

    public static int rotationDegrees(Quaterniondc savedOrientation, Vec3 spawn, Vec3 target) {
        Vector3d desired = new Vector3d(target.x - spawn.x, 0, target.z - spawn.z);
        if (desired.lengthSquared() < 1.0E-8) {
            throw new IllegalArgumentException("Spawn and HQ require different horizontal coordinates");
        }
        desired.normalize();

        int bestRotation = 0;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int rotation = -180; rotation <= 179; rotation++) {
            Quaterniond orientation = new Quaterniond(savedOrientation)
                    .mul(PlacementTargetMath.computeExtraRotation(Direction.UP, rotation));
            Vector3d forward = orientation.transform(new Vector3d(0, 0, -1));
            forward.y = 0;
            if (forward.lengthSquared() < 1.0E-8) continue;
            double dot = forward.normalize().dot(desired);
            if (dot > bestDot + 1.0E-12
                    || Math.abs(dot - bestDot) <= 1.0E-12 && Math.abs(rotation) < Math.abs(bestRotation)) {
                bestDot = dot;
                bestRotation = rotation;
            }
        }
        if (!Double.isFinite(bestDot)) throw new IllegalArgumentException("Blueprint forward direction is vertical");
        return bestRotation;
    }
}
