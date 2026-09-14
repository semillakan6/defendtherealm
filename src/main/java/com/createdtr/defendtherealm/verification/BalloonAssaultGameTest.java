package com.createdtr.defendtherealm.verification;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.integration.toolgun.ToolgunPrototypeSpawner;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import org.joml.Vector3d;

/** Opt-in fixture test. Writes only to the disposable GameTest world, never the author's save. */
@GameTestHolder(CreateDefendtheRealm.MODID)
public final class BalloonAssaultGameTest {
    @GameTestGenerator public static List<TestFunction> tests() {
        if (!ModList.get().isLoaded("create_aeronautics_toolgun")) return List.of();
        return List.of(new TestFunction("balloon", "createdefendtherealm.balloon_assault", "createdefendtherealm:empty", 9000, 0, true,
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
                var result = ToolgunPrototypeSpawner.spawnFixture(player, spawn, target);
                var targetPlayer = createNetworkedTestPlayer(helper, target.getX() + 0.5,
                        spawn.getY() - 5, target.getZ() + 55.5);
                var data = EncounterSavedData.get(level.getServer());
                var testContext = data.context();
                testContext.putUUID("fixtureTargetPlayer", targetPlayer.getUUID());
                testContext.putFloat("fixtureTargetPlayerInitialHealth", targetPlayer.getHealth());
                data.updateContext(testContext);
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
        if (context.getInt("shots") > 0 && !context.getBoolean("fixtureBarrelAlignmentVerified")) {
            Vec3 desired = new Vec3(context.getDouble("desiredBarrelX"), context.getDouble("desiredBarrelY"),
                    context.getDouble("desiredBarrelZ")).normalize();
            Vec3 actual = new Vec3(context.getDouble("actualBarrelX"), context.getDouble("actualBarrelY"),
                    context.getDouble("actualBarrelZ")).normalize();
            if (context.getDouble("aimError") <= 1 && desired.dot(actual) >= Math.cos(Math.toRadians(1))) {
                context.putBoolean("fixtureBarrelAlignmentVerified", true);
                data.updateContext(context);
            }
        }
        if (context.hasUUID("fixtureTargetPlayer") && !context.getBoolean("fixturePlayerDamageVerified")) {
            var entity = helper.getLevel().getEntity(context.getUUID("fixtureTargetPlayer"));
            if (entity instanceof net.minecraft.server.level.ServerPlayer targetPlayer
                    && targetPlayer.getHealth() < context.getFloat("fixtureTargetPlayerInitialHealth")) {
                context.putBoolean("fixturePlayerDamageVerified", true);
                context.putFloat("fixtureTargetPlayerHealthAfterHit", targetPlayer.getHealth());
                data.updateContext(context);
            }
        }
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
            helper.assertTrue(context.getBoolean("fixtureBarrelAlignmentVerified"),
                    "A real shot is authorized only after the visible post-update barrel vector aligns");
            helper.assertTrue(context.getBoolean("fixturePlayerDamageVerified"),
                    "Independent turret damages the survival player encountered before attacking the HQ; requested="
                            + context.getFloat("playerCbcDamageRequested") + " accepted="
                            + context.getBoolean("playerCbcDamageAccepted") + " observed="
                            + context.getFloat("playerHitDamageObserved") + " contacts="
                            + context.getInt("opportunityShots"));
            if (context.hasUUID("fixtureTargetPlayer")) {
                var entity = helper.getLevel().getEntity(context.getUUID("fixtureTargetPlayer"));
                if (entity instanceof net.minecraft.server.level.ServerPlayer targetPlayer)
                    helper.getLevel().getServer().getPlayerList().remove(targetPlayer);
            }
            helper.assertTrue(helper.getLevel().getGameTime() - context.getLong("targetDestroyedTick")
                    >= com.createdtr.defendtherealm.encounter.AssaultController.POST_IMPACT_LINGER_TICKS,
                    "Cleanup waits for the complete post-impact linger");
            helper.assertTrue(context.getInt("shots") > 0 && context.getInt("shots") == context.getInt("replenishments"), "Real shots replenished");
            startDamageScenario(helper, spawn);
            return;
        }
        if (helper.getLevel().getGameTime() % 100 == 0) CreateDefendtheRealm.LOGGER.info("Assault fixture: {} {}", encounter.state(), data.context());
        helper.runAfterDelay(1, () -> observe(helper, spawn));
    }

    private static void startDamageScenario(GameTestHelper helper, BlockPos spawn) {
        var level = helper.getLevel();
        BlockPos target = spawn.offset(0, -20, -100);
        level.setBlockAndUpdate(target, CreateDefendtheRealm.DEV_HQ.get().defaultBlockState());
        try {
            var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DTRDamageFixture"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            ToolgunPrototypeSpawner.spawnFixture(player, spawn, target);
            EncounterSavedData.get(level.getServer()).context().putLong("fragmentWaitStarted", level.getGameTime());
            helper.runAfterDelay(20, () -> damageToThreshold(helper));
        } catch (Exception exception) {
            release(helper, spawn);
            helper.fail("Damage fixture spawn failed: " + exception);
        }
    }

    private static void damageToThreshold(GameTestHelper helper) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter == null || !context.hasUUID("rootVehicle")) {
            helper.fail("Damage fixture has no owned root vehicle");
            return;
        }
        int minX = encounter.weights().keySet().stream().map(BlockPos::of).mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = encounter.weights().keySet().stream().map(BlockPos::of).mapToInt(BlockPos::getX).max().orElseThrow();
        // Cut just inside the port edge so the separated envelope exercises
        // lineage without deleting the central cannon mount and turning this
        // damage-threshold scenario into the separate weapon-failure case.
        int cutX = Math.min(maxX - 1, minX + 1);
        boolean changed = false;
        // Remove a complete central slice first. Random map iteration can
        // destroy many blocks without ever separating two connected regions,
        // which does not actually exercise Sable's lineage callback.
        var ordered = encounter.weights().entrySet().stream()
                .sorted(java.util.Comparator
                        .comparingInt((java.util.Map.Entry<Long, Integer> e) -> BlockPos.of(e.getKey()).getX() == cutX ? 0 : 1)
                        .thenComparingLong(java.util.Map.Entry::getKey))
                .toList();
        for (var entry : ordered) {
            if (encounter.lost().contains(entry.getKey())) continue;
            if (BlockPos.of(entry.getKey()).getX() != cutX && changed) break;
            if ((long) (encounter.lostWeight() + entry.getValue()) * 5
                    >= (long) encounter.totalWeight() * 2) break;
            BlockPos canonical = BlockPos.of(entry.getKey());
            BlockPos physical = com.createdtr.defendtherealm.integration.sable.EncounterIntegrity.physical(
                    context, context.getUUID("rootVehicle"), canonical);
            if (helper.getLevel().getBlockState(physical).isAir()) continue;
            helper.getLevel().setBlockAndUpdate(physical, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            changed = true;
            helper.assertTrue((long) encounter.lostWeight() * 5 < (long) encounter.totalWeight() * 2,
                    "Damage below 40 percent remains active");
        }
        helper.assertTrue(changed, "Physical Sable blocks were removed through the authoritative level callback");
        helper.assertTrue(encounter.lostWeight() > 0, "At least one below-threshold damage state was observed");
        // Give Sable's connectivity pass time to publish child lineage before
        // crossing the defeat boundary and invoking cleanup.
        helper.runAfterDelay(20, () -> finishDamageThreshold(helper));
    }

    private static void finishDamageThreshold(GameTestHelper helper) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        // Sable performs connectivity asynchronously. Native scheduling varies
        // between machines, so observe the bounded publication window instead
        // of assuming the split callback always arrives within exactly 20 ticks.
        if (encounter.owned().size() <= 1
                && helper.getLevel().getGameTime() - context.getLong("fragmentWaitStarted") < 200) {
            helper.runAfterDelay(1, () -> finishDamageThreshold(helper));
            return;
        }
        helper.assertTrue(encounter.owned().size() > 1,
                "Disconnected physical blocks create at least one inherited owned fragment");
        outer: for (var entry : encounter.weights().entrySet()) {
            if (encounter.lost().contains(entry.getKey())) continue;
            BlockPos canonical = BlockPos.of(entry.getKey());
            for (var vehicle : encounter.owned()) {
                BlockPos physical = com.createdtr.defendtherealm.integration.sable.EncounterIntegrity.physical(
                        context, vehicle, canonical);
                if (helper.getLevel().getBlockState(physical).isAir()) continue;
                helper.getLevel().setBlockAndUpdate(physical, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                if (encounter.state() == Encounter.State.DESTROYING) break outer;
                break;
            }
        }
        helper.assertTrue(encounter.state() == Encounter.State.DESTROYING,
                "Crossing 40 percent enters DESTROYING exactly once; got " + encounter.state()
                        + " loss=" + encounter.lostWeight() + "/" + encounter.totalWeight());
        observeDefeat(helper);
    }

    private static void observeDefeat(GameTestHelper helper) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter.state() == Encounter.State.COMPLETED) {
            helper.assertTrue(encounter.reason() == Encounter.Reason.DEFEATED, "Defeat reason persists");
            helper.assertTrue(context.getBoolean("finalBlast"), "Final non-terrain-damaging blast ran");
            helper.assertTrue(encounter.owned().isEmpty(), "All owned sublevels were acknowledged removed");
            helper.assertTrue(context.getInt("forcedChunkCount") == 0 && context.getBoolean("ticketsReleased"),
                    "All encounter chunk tickets were released");
            startCancellationScenario(helper, BlockPos.containing(context.getCompound("spawn").getInt("x"),
                    context.getCompound("spawn").getInt("y"), context.getCompound("spawn").getInt("z")));
            return;
        }
        helper.runAfterDelay(1, () -> observeDefeat(helper));
    }

    private static void startCancellationScenario(GameTestHelper helper, BlockPos spawn) {
        var level = helper.getLevel();
        BlockPos cancelSpawn = spawn.offset(80, 0, 0);
        BlockPos target = cancelSpawn.offset(0, -20, -100);
        // setChunkForced is asynchronous; explicitly materialize this separate
        // corridor before validating the third consecutive placement.
        for (int x = (cancelSpawn.getX() >> 4) - 1; x <= (cancelSpawn.getX() >> 4) + 1; x++)
            for (int z = (target.getZ() >> 4) - 1; z <= (cancelSpawn.getZ() >> 4) + 1; z++)
                level.getChunk(x, z);
        level.setBlockAndUpdate(target, CreateDefendtheRealm.DEV_HQ.get().defaultBlockState());
        try {
            var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DTRCancelFixture"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            ToolgunPrototypeSpawner.spawnFixture(player, cancelSpawn, target);
            try {
                ToolgunPrototypeSpawner.spawnFixture(player, cancelSpawn.offset(30, 0, 0), target);
                helper.fail("A duplicate active encounter was accepted");
                return;
            } catch (IllegalArgumentException expected) {
                helper.assertTrue(expected.getMessage().contains("already active"),
                        "Duplicate rejection reports the active encounter limit");
            }
            helper.runAfterDelay(20, () -> {
                var data = EncounterSavedData.get(level.getServer());
                data.encounter().terminate(Encounter.Reason.CANCELLED);
                data.setDirty();
                observeCancellation(helper, spawn);
            });
        } catch (Exception exception) {
            release(helper, spawn);
            helper.fail("Cancellation fixture spawn failed: " + exception);
        }
    }

    private static void observeCancellation(GameTestHelper helper, BlockPos spawn) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter.state() == Encounter.State.COMPLETED) {
            helper.assertTrue(encounter.reason() == Encounter.Reason.CANCELLED,
                    "Administrative cancellation retains its terminal reason");
            helper.assertTrue(encounter.owned().isEmpty(), "Cancellation removes every owned sublevel");
            helper.assertTrue(context.getInt("forcedChunkCount") == 0 && context.getBoolean("ticketsReleased"),
                    "Cancellation releases every encounter ticket");
            startBlockedScenario(helper, spawn);
            return;
        }
        helper.runAfterDelay(1, () -> observeCancellation(helper, spawn));
    }

    private static void startBlockedScenario(GameTestHelper helper, BlockPos spawn) {
        var level = helper.getLevel();
        BlockPos target = spawn.offset(0, -20, -100);
        for (int x = (spawn.getX() >> 4) - 8; x <= (spawn.getX() >> 4) + 8; x++)
            for (int z = (spawn.getZ() >> 4) - 12; z <= (spawn.getZ() >> 4) + 8; z++)
                level.setChunkForced(x, z, true);
        for (int x = (spawn.getX() >> 4) - 1; x <= (spawn.getX() >> 4) + 1; x++)
            for (int z = (spawn.getZ() >> 4) - 1; z <= (spawn.getZ() >> 4) + 1; z++)
                level.getChunk(x, z);
        level.setBlockAndUpdate(target, CreateDefendtheRealm.DEV_HQ.get().defaultBlockState());
        // A tall, finite wall blocks the initial firing lane but leaves valid
        // positions around either edge for the tactical orbit planner.
        for (int x = target.getX() - 12; x <= target.getX() + 12; x++)
            for (int y = target.getY() - 10; y <= target.getY() + 40; y++)
                level.setBlockAndUpdate(new BlockPos(x, y, target.getZ() + 8),
                        net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState());
        try {
            var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DTROrbitFixture"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            var result = ToolgunPrototypeSpawner.spawnFixture(player, spawn, target);
            var container = SubLevelContainer.getContainer(level);
            var placed = container == null ? null : container.getSubLevel(result.vehicleId());
            helper.assertTrue(placed instanceof ServerSubLevel, "Blocked-lane vehicle is available");
            var ship = (ServerSubLevel) placed;
            var context = EncounterSavedData.get(level.getServer()).context();
            context.putLong("fixtureBlockedStarted", level.getGameTime());
            context.putDouble("fixtureOrbitStartX", ship.logicalPose().position().x());
            context.putDouble("fixtureOrbitStartY", ship.logicalPose().position().y());
            context.putDouble("fixtureOrbitStartZ", ship.logicalPose().position().z());
            EncounterSavedData.get(level.getServer()).updateContext(context);
            observeBlockedScenario(helper, spawn, target);
        } catch (Exception exception) {
            clearWall(helper, target);
            release(helper, spawn);
            helper.fail("Blocked-lane fixture spawn failed: " + exception);
        }
    }

    private static void observeBlockedScenario(GameTestHelper helper, BlockPos spawn, BlockPos target) {
        var data = EncounterSavedData.get(helper.getLevel().getServer());
        var encounter = data.encounter();
        var context = data.context();
        helper.assertTrue(context.getInt("breachShots") <= 3,
                "A blocked target receives at most three total breach shots; got " + context.getInt("breachShots"));
        if (context.getLongArray("maneuverRoute").length > 0) {
            double dx = context.getDouble("vehicleX") - context.getDouble("fixtureOrbitStartX");
            double dy = context.getDouble("vehicleY") - context.getDouble("fixtureOrbitStartY");
            double dz = context.getDouble("vehicleZ") - context.getDouble("fixtureOrbitStartZ");
            context.putBoolean("fixtureManeuverObserved", true);
            context.putDouble("fixtureMaximumManeuverDisplacement", Math.max(
                    context.getDouble("fixtureMaximumManeuverDisplacement"), Math.sqrt(dx * dx + dy * dy + dz * dz)));
            data.updateContext(context);
        }
        if (encounter.state() == Encounter.State.COMPLETED) {
            clearWall(helper, target);
            release(helper, spawn);
            helper.assertTrue(encounter.reason() == Encounter.Reason.TARGET_DESTROYED
                            || encounter.reason() == Encounter.Reason.OBSTRUCTED,
                    "Blocked lane has a bounded tactical outcome, got " + encounter.reason());
            helper.assertTrue(encounter.reason() != Encounter.Reason.TIMEOUT,
                    "Blocked-lane recovery must not wait for the global encounter timeout");
            helper.assertTrue(helper.getLevel().getGameTime() - context.getLong("fixtureBlockedStarted") < 1800,
                    "Blocked-lane recovery completes within its tactical deadline");
            if (context.getBoolean("fixtureManeuverObserved"))
                helper.assertTrue(context.getDouble("fixtureMaximumManeuverDisplacement") > 3,
                        "An accepted orbit route produces meaningful vehicle displacement");
            helper.assertTrue(encounter.owned().isEmpty(), "Blocked-lane cleanup removes every owned sublevel");
            helper.assertTrue(context.getInt("forcedChunkCount") == 0 && context.getBoolean("ticketsReleased"),
                    "Blocked-lane cleanup releases every encounter ticket");
            helper.succeed();
            return;
        }
        helper.runAfterDelay(1, () -> observeBlockedScenario(helper, spawn, target));
    }

    private static void clearWall(GameTestHelper helper, BlockPos target) {
        for (int x = target.getX() - 12; x <= target.getX() + 12; x++)
            for (int y = target.getY() - 10; y <= target.getY() + 40; y++)
                helper.getLevel().setBlockAndUpdate(new BlockPos(x, y, target.getZ() + 8),
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }

    private static void release(GameTestHelper helper, BlockPos spawn) {
        for (int x = (spawn.getX() >> 4) - 8; x <= (spawn.getX() >> 4) + 8; x++)
            for (int z = (spawn.getZ() >> 4) - 12; z <= (spawn.getZ() >> 4) + 8; z++) helper.getLevel().setChunkForced(x, z, false);
    }

    private static net.minecraft.server.level.ServerPlayer createNetworkedTestPlayer(GameTestHelper helper,
            double x, double y, double z) {
        var level = helper.getLevel();
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DTRSurvivalTarget");
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new net.minecraft.server.level.ServerPlayer(level.getServer(), level, profile,
                cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(x, y, z);
        player.setNoGravity(true);
        return player;
    }
}
