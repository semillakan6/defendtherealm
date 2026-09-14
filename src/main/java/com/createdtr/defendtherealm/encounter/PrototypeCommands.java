package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.createdtr.defendtherealm.template.TemplateSavedData;
import com.createdtr.defendtherealm.integration.sable.SableVehicles;
import com.createdtr.defendtherealm.integration.toolgun.ToolgunPrototypeSpawner;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class PrototypeCommands {
    private PrototypeCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dtr").requires(source -> source.hasPermission(2))
            .then(Commands.literal("prototype")
                .then(Commands.literal("vehicles").executes(ctx -> {
                    var vehicles = SableVehicles.inspect(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(() -> Component.literal("Loaded vehicles: " + vehicles.size()), false);
                    vehicles.stream().limit(32).forEach(vehicle -> ctx.getSource().sendSuccess(
                        () -> Component.literal(vehicle.toString()), false));
                    return vehicles.size();
                }))
                .then(Commands.literal("inspect").executes(ctx -> inspect(ctx.getSource())))
                .then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource())))
                .then(Commands.literal("capture").then(Commands.argument("template", StringArgumentType.word())
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                        .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(ctx -> {
                            try {
                                int count = TemplateSavedData.get(ctx.getSource().getLevel()).capture(
                                    ctx.getSource().getLevel(), StringArgumentType.getString(ctx, "template"),
                                    BlockPosArgument.getBlockPos(ctx, "from"), BlockPosArgument.getBlockPos(ctx, "to"));
                                ctx.getSource().sendSuccess(() -> Component.literal("Captured " + count
                                    + " blocks with supported machinery settings. This is not a validated armed-vehicle template."), false);
                                return 1;
                            } catch (IllegalArgumentException ex) {
                                ctx.getSource().sendFailure(Component.literal(ex.getMessage()));
                                return 0;
                            }
                        })))))
                .then(Commands.literal("fixture").then(Commands.argument("spawn", BlockPosArgument.blockPos())
                    .executes(ctx -> {
                        if (!ModList.get().isLoaded("create_aeronautics_toolgun")) {
                            ctx.getSource().sendFailure(Component.literal("Toolgun is not loaded; prototype blueprint spawning is unavailable."));
                            return 0;
                        }
                        try {
                            BlockPos spawn = BlockPosArgument.getBlockPos(ctx, "spawn");
                            var result = ToolgunPrototypeSpawner.spawnFixture(ctx.getSource().getPlayerOrException(), spawn,
                                    com.createdtr.defendtherealm.hq.HqSavedData.get(ctx.getSource().getLevel()).nearest(
                                            ctx.getSource().getLevel(), spawn));
                            ctx.getSource().sendSuccess(() -> Component.literal("Bundled Test Ballon spawned: encounter="
                                    + result.encounterId() + " vehicle=" + result.vehicleId() + " blocks="
                                    + result.blocks() + " ammunition=" + result.ammunition()), true);
                            return 1;
                        } catch (RuntimeException | java.io.IOException ex) {
                            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                            ctx.getSource().sendFailure(Component.literal("Prototype not spawned: " + message));
                            return 0;
                        }
                    })))
                .then(Commands.literal("start").then(Commands.argument("template", StringArgumentType.string())
                    .then(Commands.argument("spawn", BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            if (!ModList.get().isLoaded("create_aeronautics_toolgun")) {
                                ctx.getSource().sendFailure(Component.literal("Toolgun is not loaded; prototype blueprint spawning is unavailable."));
                                return 0;
                            }
                            try {
                                var result = ToolgunPrototypeSpawner.spawn(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "template"),
                                        BlockPosArgument.getBlockPos(ctx, "spawn"),
                                        com.createdtr.defendtherealm.hq.HqSavedData.get(ctx.getSource().getLevel()).nearest(
                                                ctx.getSource().getLevel(), BlockPosArgument.getBlockPos(ctx, "spawn")));
                                ctx.getSource().sendSuccess(() -> Component.literal("Prototype spawned: encounter="
                                        + result.encounterId() + " vehicle=" + result.vehicleId() + " blocks="
                                        + result.blocks() + " ammunition=" + result.ammunition() + " state="
                                        + (result.machineryReady() ? "APPROACHING" : "SPAWNING")), true);
                                return 1;
                            } catch (RuntimeException | java.io.IOException ex) {
                                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                                ctx.getSource().sendFailure(Component.literal("Prototype not spawned: " + message));
                                return 0;
                            }
                        }))))));
    }

    private static int inspect(CommandSourceStack source) {
        var data = EncounterSavedData.get(source.getServer());
        Encounter encounter = data.encounter();
        var context = data.context();
        source.sendSuccess(() -> Component.literal("Controller: " + context), false);
        source.sendSuccess(() -> Component.literal(encounter == null ? "No recorded prototype encounter. Armed integration pending."
            : "Encounter=" + encounter.id() + " state=" + encounter.state() + " reason=" + encounter.reason()
                + " integrity=" + (100 - encounter.lostWeight() * 100 / encounter.totalWeight()) + "%"
                + " lostWeight=" + encounter.lostWeight() + "/" + encounter.totalWeight()
                + " owned=" + encounter.owned() + " tickets=" + context.getInt("forcedChunkCount")
                + " projectiles=" + context.getList("ownedProjectiles", net.minecraft.nbt.Tag.TAG_COMPOUND).size()
                + " shots=" + context.getInt("shots") + " replenishments=" + context.getInt("replenishments")
                + " tactical=" + context.getString("tacticalPhase")
                + " orbitAttempts=" + context.getInt("orbitAttempts")
                + " maneuver=" + context.getInt("maneuverCursor") + "/"
                + context.getLongArray("maneuverRoute").length
                + " maneuverDistance=" + context.getDouble("maneuverWaypointDistance")
                + " maneuverStalledTicks=" + context.getInt("maneuverStalledTicks")
                + " orbitFailure=" + context.getString("lastOrbitFailure")
                + " grounded=" + context.getBoolean("defeatGrounded")
                + " defeatTicksRemaining=" + context.getInt("defeatTicksRemaining")), false);
        if (encounter != null && context.contains("combatProfile")) {
            source.sendSuccess(() -> Component.literal("Combat profile=" + context.getString("combatProfile")
                    + " vehicleFamily=" + context.getString("vehicleFamily")
                    + " navigation=" + context.getString("navigationProfile")), false);
            for (net.minecraft.nbt.Tag value : context.getList("weaponStates", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var weapon = (net.minecraft.nbt.CompoundTag) value;
                source.sendSuccess(() -> Component.literal("Weapon=" + weapon.getString("id")
                        + " behavior=" + weapon.getString("behavior")
                        + " target=" + weapon.getString("targetType") + ":" + weapon.getString("targetKey")
                        + " range=" + weapon.getDouble("minimumRange") + ".." + weapon.getDouble("maximumRange")
                        + " preferred=" + weapon.getDouble("preferredMinimumRange") + ".."
                        + weapon.getDouble("preferredMaximumRange")
                        + " aimError=" + weapon.getDouble("aimError")
                        + " clear=" + weapon.getBoolean("clearShot")
                        + " trajectory=" + weapon.getString("trajectoryType")
                        + " solution=" + weapon.getString("fireSolution")
                        + " flightTicks=" + weapon.getDouble("predictedFlightTicks")
                        + " shots=" + weapon.getInt("shots")), false);
            }
        }
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        EncounterSavedData data = EncounterSavedData.get(source.getServer());
        Encounter encounter = data.encounter();
        if (encounter == null || encounter.state() == Encounter.State.COMPLETED) {
            source.sendSuccess(() -> Component.literal("No active encounter."), false);
            return 1;
        }
        encounter.terminate(Encounter.Reason.CANCELLED);
        data.setDirty();
        source.sendSuccess(() -> Component.literal("Encounter cancelled; combat disabled and shared cleanup scheduled for "
                + encounter.owned().size() + " owned vehicle object(s)."), true);
        return 1;
    }
}
