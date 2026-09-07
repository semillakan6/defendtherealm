package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.createdtr.defendtherealm.template.TemplateSavedData;
import com.createdtr.defendtherealm.integration.sable.SableVehicles;
import com.createdtr.defendtherealm.integration.toolgun.ToolgunPrototypeSpawner;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("start").then(Commands.argument("template", StringArgumentType.string())
                    .then(Commands.argument("spawn", BlockPosArgument.blockPos())
                        .then(Commands.argument("target", BlockPosArgument.blockPos()).executes(ctx -> {
                            if (!ModList.get().isLoaded("create_aeronautics_toolgun")) {
                                ctx.getSource().sendFailure(Component.literal("Toolgun is not loaded; prototype blueprint spawning is unavailable."));
                                return 0;
                            }
                            try {
                                var result = ToolgunPrototypeSpawner.spawn(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "template"),
                                        BlockPosArgument.getBlockPos(ctx, "spawn"),
                                        BlockPosArgument.getBlockPos(ctx, "target"));
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
                        })))))));
    }

    private static int inspect(CommandSourceStack source) {
        Encounter encounter = EncounterSavedData.get(source.getServer()).encounter();
        source.sendSuccess(() -> Component.literal(encounter == null ? "No recorded prototype encounter. Armed integration pending."
            : "Encounter=" + encounter.id() + " state=" + encounter.state() + " reason=" + encounter.reason()
                + " lostWeight=" + encounter.lostWeight() + "/" + encounter.totalWeight() + " owned=" + encounter.owned()), false);
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
        var cleanup = SableVehicles.removeOwned(source.getLevel(), encounter);
        if (encounter.owned().isEmpty()) encounter.advance(Encounter.State.COMPLETED);
        data.setDirty();
        source.sendSuccess(() -> Component.literal(encounter.state() == Encounter.State.COMPLETED
            ? "Encounter cancelled; removed " + cleanup.removed() + " vehicle object(s)."
            : "Combat disabled; removed " + cleanup.removed() + " vehicle object(s), "
                + cleanup.pending() + " unloaded object(s) remain pending."), true);
        return 1;
    }
}
