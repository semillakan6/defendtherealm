package com.createdtr.defendtherealm.combat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Pure trajectory solver. CBC still owns projectile creation, motion and impact. */
public final class BallisticFireControl {
    private static final double EPSILON = 1.0e-8;
    private BallisticFireControl() {}

    public static FireSolution solve(Vec3 muzzle, Vec3 target, Vec3 targetVelocity, TrajectoryProfile profile) {
        if (!finite(muzzle) || !finite(target) || !finite(targetVelocity))
            return FireSolution.unreachable("non_finite_input");
        Vec3 predicted = target;
        FireSolution solution = FireSolution.unreachable("no_solution");
        // Iterate lead time because the ballistic solution itself changes the intercept point.
        for (int i = 0; i < 6; i++) {
            solution = solveStationary(muzzle, predicted, profile);
            if (!solution.reachable()) return solution;
            predicted = target.add(targetVelocity.scale(solution.flightTicks()));
        }
        return solveStationary(muzzle, predicted, profile);
    }

    static FireSolution solveStationary(Vec3 muzzle, Vec3 target, TrajectoryProfile profile) {
        Vec3 delta = target.subtract(muzzle);
        double horizontal = Math.hypot(delta.x, delta.z);
        double speed = profile.projectileSpeed();
        double gravity = profile.gravityPerTick();
        if (delta.lengthSqr() < EPSILON) return FireSolution.unreachable("target_at_muzzle");

        double pitch = findPitch(horizontal, delta.y, profile);
        if (!Double.isFinite(pitch)) return FireSolution.unreachable("ballistic_range");

        Vec3 horizontalDirection = horizontal < EPSILON ? Vec3.ZERO : new Vec3(delta.x / horizontal, 0, delta.z / horizontal);
        Vec3 launch = horizontalDirection.scale(Math.cos(pitch)).add(0, Math.sin(pitch), 0).normalize();
        double flight = flightTime(horizontal, speed * Math.cos(pitch), profile.dragPerTick());
        if (!Double.isFinite(flight) || flight <= 0 || flight > profile.maximumFlightTicks())
            return FireSolution.unreachable("flight_time");

        List<Vec3> path = sample(muzzle, launch.scale(speed), gravity, profile.dragPerTick(), flight);
        Vec3 impact = path.getLast();
        if (impact.distanceTo(target) > 0.5) return FireSolution.unreachable("trajectory_error");
        return new FireSolution(true, launch, impact, flight, path, "ready");
    }

    private static double findPitch(double horizontal, double height, TrajectoryProfile profile) {
        if (horizontal < EPSILON) return height >= 0 ? Math.PI / 2 : -Math.PI / 2;
        List<Double> roots = new ArrayList<>();
        double previousPitch = Math.toRadians(-89);
        double previous = verticalError(previousPitch, horizontal, height, profile);
        for (int degrees = -88; degrees <= 89; degrees++) {
            double pitch = Math.toRadians(degrees);
            double value = verticalError(pitch, horizontal, height, profile);
            if (Double.isFinite(previous) && Double.isFinite(value) && Math.signum(previous) != Math.signum(value)) {
                double low = previousPitch;
                double high = pitch;
                for (int i = 0; i < 32; i++) {
                    double middle = (low + high) * 0.5;
                    double middleValue = verticalError(middle, horizontal, height, profile);
                    if (Math.signum(middleValue) == Math.signum(verticalError(low, horizontal, height, profile))) low = middle;
                    else high = middle;
                }
                roots.add((low + high) * 0.5);
            }
            previousPitch = pitch;
            previous = value;
        }
        if (roots.isEmpty()) return Double.NaN;
        return profile.type() == TrajectoryType.BALLISTIC_HIGH_ARC ? roots.getLast() : roots.getFirst();
    }

    private static double verticalError(double pitch, double horizontal, double height, TrajectoryProfile profile) {
        double time = flightTime(horizontal, profile.projectileSpeed() * Math.cos(pitch), profile.dragPerTick());
        if (!Double.isFinite(time) || time <= 0 || time > profile.maximumFlightTicks()) return Double.NaN;
        return displacement(profile.projectileSpeed() * Math.sin(pitch), profile.gravityPerTick(),
                profile.dragPerTick(), time) - height;
    }

    private static double flightTime(double distance, double initialSpeed, double drag) {
        if (initialSpeed <= EPSILON) return Double.NaN;
        if (drag < EPSILON) return distance / initialSpeed;
        double retained = 1 - drag;
        double scaled = distance * (1 - retained) / initialSpeed;
        if (scaled >= 1) return Double.NaN;
        return Math.log1p(-scaled) / Math.log(retained);
    }

    private static double displacement(double initialSpeed, double gravity, double drag, double time) {
        if (drag < EPSILON) return initialSpeed * time - 0.5 * gravity * time * time;
        double retained = 1 - drag;
        double series = (1 - Math.pow(retained, time)) / (1 - retained);
        // CBC applies drag and gravity once per projectile tick. This closed
        // form follows that linear recurrence without spawning probe entities.
        return initialSpeed * series - gravity / (1 - retained) * (time - series);
    }

    private static List<Vec3> sample(Vec3 origin, Vec3 initialVelocity, double gravity, double drag, double flight) {
        int steps = Math.max(1, (int) Math.ceil(flight * 4));
        double dt = flight / steps;
        List<Vec3> points = new ArrayList<>(steps + 1);
        points.add(origin);
        for (int i = 1; i <= steps; i++) {
            double time = dt * i;
            double horizontalScale = drag < EPSILON ? time
                    : (1 - Math.pow(1 - drag, time)) / drag;
            points.add(origin.add(initialVelocity.x * horizontalScale,
                    displacement(initialVelocity.y, gravity, drag, time),
                    initialVelocity.z * horizontalScale));
        }
        return List.copyOf(points);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x + value.y + value.z);
    }
}
