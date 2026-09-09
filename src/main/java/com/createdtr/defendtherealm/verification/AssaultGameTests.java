package com.createdtr.defendtherealm.verification;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.hq.HqSavedData;
import com.createdtr.defendtherealm.navigation.HoverAirshipPlanner;
import com.createdtr.defendtherealm.navigation.VehicleRoutePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateDefendtheRealm.MODID)
@PrefixGameTestTemplate(false)
public final class AssaultGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hqRegistration(GameTestHelper helper) {
        BlockPos relative = new BlockPos(4, 4, 4);
        BlockPos world = helper.absolutePos(relative);
        helper.setBlock(relative, CreateDefendtheRealm.DEV_HQ.get());
        helper.assertTrue(HqSavedData.get(helper.getLevel()).nearest(helper.getLevel(), world).equals(world), "Placed HQ is detected");
        helper.setBlock(relative, Blocks.AIR);
        var saved = HqSavedData.get(helper.getLevel()).save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(java.util.Arrays.stream(saved.getLongArray("positions")).noneMatch(p -> p == world.asLong()), "Removed HQ is unregistered");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void vanillaDetour(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(5, 10, 10));
        BlockPos goal = helper.absolutePos(new BlockPos(17, 10, 10));
        var envelope = new VehicleRoutePlanner.Envelope(0.4, 0.4, 0.4);
        helper.setBlock(new BlockPos(11, 10, 10), Blocks.STONE);
        var route = new HoverAirshipPlanner().plan(level, start, goal, envelope);
        helper.assertTrue(!route.isEmpty() && route.getLast().equals(goal), "Vanilla A* reaches target around obstruction");
        for (int i = 1; i < route.size(); i++) helper.assertTrue(VehicleRoutePlanner.clearSegment(level,
                Vec3.atCenterOf(route.get(i - 1)), Vec3.atCenterOf(route.get(i)), envelope), "Every detour edge is clear");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sweptClearance(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 start = Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 8, 8)));
        Vec3 goal = Vec3.atCenterOf(helper.absolutePos(new BlockPos(18, 8, 8)));
        var envelope = new VehicleRoutePlanner.Envelope(1.1, 1, 1);
        helper.assertTrue(VehicleRoutePlanner.clearSegment(level, start, goal, envelope), "Open corridor is clear");
        helper.setBlock(new BlockPos(10, 8, 9), Blocks.STONE);
        helper.assertTrue(!VehicleRoutePlanner.clearSegment(level, start, goal, envelope), "Full width catches off-center obstruction");
        helper.setBlock(new BlockPos(10, 8, 9), Blocks.AIR);
        var route = new HoverAirshipPlanner().plan(level, BlockPos.containing(start), BlockPos.containing(goal), envelope);
        helper.assertTrue(route.size() > 2 && route.getLast().equals(BlockPos.containing(goal)), "Route includes recovery anchors and reaches goal");
        helper.succeed();
    }
}
