package com.createdtr.defendtherealm.verification;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.integration.toolgun.ToolgunPrototypeSpawner;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.enxv.aeronauticsstructuretool.blueprint.storage.BlueprintFileRepository;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3d;

/** Opt-in fixture test. Writes only to the disposable GameTest world, never the author's save. */
@GameTestHolder(CreateDefendtheRealm.MODID)
public final class BalloonAssaultGameTest {
    @GameTestGenerator public static List<TestFunction> tests() {
        if (System.getProperty("dtr.assaultBlueprint") == null || !ModList.get().isLoaded("create_aeronautics_toolgun")) return List.of();
        return List.of(new TestFunction("balloon", "createdefendtherealm.balloon_assault", "createdefendtherealm:empty", 7400, 0, true,
                BalloonAssaultGameTest::assault));
    }
    private static void assault(GameTestHelper helper) {
        var level = helper.getLevel();
        // Keep the entire firing lane beyond the GameTest structure's barrier
        // shell. The shell is test infrastructure, not an assault obstacle.
        BlockPos target = helper.absolutePos(new BlockPos(12, 12, 40));
        BlockPos spawn = target.offset(0, 20, 100);
        // Test-owned loading only; runtime assaults never force terrain chunks.
        for (int x = (spawn.getX() >> 4) - 8; x <= (spawn.getX() >> 4) + 8; x++)
            for (int z = (spawn.getZ() >> 4) - 12; z <= (spawn.getZ() >> 4) + 8; z++) level.setChunkForced(x, z, true);
        helper.runAfterDelay(20, () -> {
            try {
                // GameTest may finish clearing its surrounding footprint after
                // the function is scheduled, so install the external target at
                // the same moment the assault begins.
                level.setBlockAndUpdate(target, CreateDefendtheRealm.DEV_HQ.get().defaultBlockState());
                // Placement needs an owner identity, not a connected client. Joining a mock
                // player triggers Simulated custom-payload negotiation that GameTest lacks.
                var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                        new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DTRFixture"),
                        net.minecraft.server.level.ClientInformation.createDefault());
                var dir = BlueprintFileRepository.serverDirectory(level.getServer().getWorldPath(LevelResource.ROOT), player.getUUID());
                BlueprintFileRepository.write(dir, "dtr_fixture", Files.readAllBytes(Path.of(System.getProperty("dtr.assaultBlueprint"))));
                var result = ToolgunPrototypeSpawner.spawn(player, "dtr_fixture", spawn, target);
                var container = SubLevelContainer.getContainer(level);
                var placed = container == null ? null : container.getSubLevel(result.vehicleId());
                helper.assertTrue(placed instanceof ServerSubLevel, "Placed root vehicle is available");
                var ship = (ServerSubLevel) placed;
                Vector3d forward = ship.logicalPose().transformNormal(new Vector3d(0, 0, -1));
                forward.y = 0;
                Vector3d desired = new Vector3d(target.getX() + 0.5 - ship.logicalPose().position().x(), 0,
                        target.getZ() + 0.5 - ship.logicalPose().position().z()).normalize();
                helper.assertTrue(forward.normalize().dot(desired) > 0.9998,
                        "Vehicle faces HQ before assisted steering");
                observe(helper, spawn);
            } catch (Exception ex) { release(helper, spawn); helper.fail("Fixture spawn failed: " + ex); }
        });
    }
    private static void observe(GameTestHelper helper, BlockPos spawn) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter.state() == Encounter.State.ENGAGING
                && context.getBoolean("targetDestroyedByProjectile")) {
            var container = SubLevelContainer.getContainer(helper.getLevel());
            var ship = container == null ? null : container.getSubLevel(context.getUUID("rootVehicle"));
            helper.assertTrue(ship instanceof ServerSubLevel && !ship.isRemoved(),
                    "Ship remains present during post-impact linger");
            helper.assertTrue(context.getInt("postImpactTicksRemaining") > 0,
                    "Post-impact linger remains active before cleanup");
        }
        if (encounter.state() == Encounter.State.COMPLETED) {
            release(helper, spawn);
            helper.assertTrue(encounter.reason() == Encounter.Reason.TARGET_DESTROYED,
                    "Expected HQ destruction, got " + encounter.reason()
                            + " shots=" + context.getInt("shots")
                            + " fireRequests=" + context.getInt("fireRequests")
                            + " failure=" + context.getString("controllerFailure"));
            helper.assertTrue(context.getBoolean("targetDestroyedByProjectile"), "CBC projectile destruction is recorded");
            helper.assertTrue(helper.getLevel().getGameTime() - context.getLong("targetDestroyedTick")
                    >= com.createdtr.defendtherealm.encounter.AssaultController.POST_IMPACT_LINGER_TICKS,
                    "Cleanup waits for the complete post-impact linger");
            helper.assertTrue(context.getInt("shots") > 0 && context.getInt("shots") == context.getInt("replenishments"), "Real shots replenished");
            helper.succeed(); return;
        }
        if (helper.getLevel().getGameTime() % 100 == 0) CreateDefendtheRealm.LOGGER.info("Assault fixture: {} {}", encounter.state(), data.context());
        helper.runAfterDelay(1, () -> observe(helper, spawn));
    }
    private static void release(GameTestHelper helper, BlockPos spawn) {
        for (int x = (spawn.getX() >> 4) - 8; x <= (spawn.getX() >> 4) + 8; x++)
            for (int z = (spawn.getZ() >> 4) - 12; z <= (spawn.getZ() >> 4) + 8; z++) helper.getLevel().setChunkForced(x, z, false);
    }
}
