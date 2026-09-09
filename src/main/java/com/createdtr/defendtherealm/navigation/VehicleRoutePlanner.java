package com.createdtr.defendtherealm.navigation;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface VehicleRoutePlanner {
    record Envelope(double radius, double below, double above) {
        public Envelope {
            if (!Double.isFinite(radius + below + above) || radius <= 0 || below < 0 || above < 0)
                throw new IllegalArgumentException("Invalid vehicle envelope");
        }
    }
    List<BlockPos> plan(ServerLevel level, BlockPos start, BlockPos goal, Envelope envelope);

    static boolean clear(ServerLevel level, Vec3 point, Envelope e) {
        BlockPos min = BlockPos.containing(point.x - e.radius(), point.y - e.below(), point.z - e.radius());
        BlockPos max = BlockPos.containing(point.x + e.radius(), point.y + e.above(), point.z + e.radius());
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) return false;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
            if (!level.getFluidState(pos).isEmpty() || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        }
        return true;
    }

    static boolean clearSegment(ServerLevel level, Vec3 from, Vec3 to, Envelope e) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 2));
        if (steps > 2048) return false;
        for (int i = 0; i <= steps; i++) if (!clear(level, from.lerp(to, (double) i / steps), e)) return false;
        return true;
    }
}
