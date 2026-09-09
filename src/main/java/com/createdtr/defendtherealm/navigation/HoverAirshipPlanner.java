package com.createdtr.defendtherealm.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.Vec3;

/** Reuses vanilla A*; evaluator owns vehicle clearance and never adds the adapter mob to the world. */
public final class HoverAirshipPlanner implements VehicleRoutePlanner {
    @Override public List<BlockPos> plan(ServerLevel level, BlockPos start, BlockPos goal, Envelope envelope) {
        if (start.distSqr(goal) > 512D * 512D) return List.of();
        if (VehicleRoutePlanner.clearSegment(level, Vec3.atCenterOf(start), Vec3.atCenterOf(goal), envelope)) {
            List<BlockPos> direct = new ArrayList<>();
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(start.distSqr(goal)) / 4));
            for (int i = 0; i <= steps; i++) direct.add(BlockPos.containing(Vec3.atCenterOf(start).lerp(Vec3.atCenterOf(goal), (double) i / steps)));
            return List.copyOf(direct);
        }
        BlockPos min = new BlockPos(Math.min(start.getX(), goal.getX()) - 32,
                Math.max(level.getMinBuildHeight(), Math.min(start.getY(), goal.getY()) - 32), Math.min(start.getZ(), goal.getZ()) - 32);
        BlockPos max = new BlockPos(Math.max(start.getX(), goal.getX()) + 32,
                Math.min(level.getMaxBuildHeight() - 1, Math.max(start.getY(), goal.getY()) + 32), Math.max(start.getZ(), goal.getZ()) + 32);
        // PathNavigationRegion can retrieve chunks: check availability before constructing it.
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++)
            for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++)
                if (!level.hasChunk(x, z)) return List.of();
        Mob adapter = EntityType.BAT.create(level);
        if (adapter == null) return List.of();
        adapter.setPos(Vec3.atCenterOf(start));
        var evaluator = new Evaluator(level, start, min, max, envelope);
        Path path = new PathFinder(evaluator, 4096).findPath(new PathNavigationRegion(level, min, max), adapter, Set.of(goal), 768, 0, 1);
        if (path == null || !path.canReach()) return List.of();
        List<BlockPos> result = new ArrayList<>();
        for (int i = 0; i < path.getNodeCount(); i++) result.add(path.getNodePos(i).immutable());
        return List.copyOf(result);
    }

    private static final class Evaluator extends NodeEvaluator {
        private final ServerLevel level;
        private final BlockPos start, min, max;
        private final Envelope envelope;
        private final Map<BlockPos, Boolean> clearance = new HashMap<>();
        Evaluator(ServerLevel level, BlockPos start, BlockPos min, BlockPos max, Envelope envelope) {
            this.level = level; this.start = start; this.min = min; this.max = max; this.envelope = envelope;
        }
        private boolean clear(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ()
                    && clearance.computeIfAbsent(p.immutable(), key -> VehicleRoutePlanner.clear(level, Vec3.atCenterOf(key), envelope));
        }
        @Override public Node getStart() { return getNode(start); }
        @Override public Target getTarget(double x, double y, double z) { return getTargetNodeAt(x, y, z); }
        @Override public int getNeighbors(Node[] output, Node node) {
            int count = 0;
            BlockPos pos = new BlockPos(node.x, node.y, node.z);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (clear(next) && VehicleRoutePlanner.clearSegment(level, Vec3.atCenterOf(pos), Vec3.atCenterOf(next), envelope)) {
                    Node value = getNode(next); value.type = PathType.OPEN;
                    if (!value.closed) output[count++] = value;
                }
            }
            return count;
        }
        @Override public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) { return getPathType(context, x, y, z); }
        @Override public PathType getPathType(PathfindingContext context, int x, int y, int z) { return clear(new BlockPos(x, y, z)) ? PathType.OPEN : PathType.BLOCKED; }
    }
}
