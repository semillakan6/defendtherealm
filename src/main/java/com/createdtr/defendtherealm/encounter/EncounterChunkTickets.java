package com.createdtr.defendtherealm.encounter;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/** Bounded, encounter-owned parent-world and Sable loading tickets. */
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class EncounterChunkTickets {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CreateDefendtheRealm.MODID, "prototype_encounter");
    private static final TicketController PARENT = new TicketController(ID, (level, helper) -> {
        var data = EncounterSavedData.get(level.getServer());
        var encounter = data.encounter();
        var context = data.context();
        if (encounter == null || encounter.state() == Encounter.State.COMPLETED
                || !level.dimension().location().toString().equals(context.getString("dimension"))) {
            for (BlockPos owner : Set.copyOf(helper.getBlockTickets().keySet())) helper.removeAllTickets(owner);
        }
    });
    private static final SubLevelLoadingTicketType<String> SUBLEVEL =
            SubLevelLoadingTicketType.create(ID, Codec.STRING);

    private EncounterChunkTickets() {}

    @SubscribeEvent
    public static void register(RegisterTicketControllersEvent event) { event.register(PARENT); }

    public static void update(ServerLevel level, EncounterSavedData data, ServerSubLevel ship,
            BlockPos current, BlockPos next, BlockPos hq) {
        var encounter = data.encounter();
        if (encounter == null) return;
        var context = data.context();
        BlockPos owner = context.contains("ticketOwner")
                ? AssaultController.position(context.getCompound("ticketOwner")) : current;
        if (!context.contains("ticketOwner")) {
            var ownerTag = new net.minecraft.nbt.CompoundTag();
            ownerTag.putInt("x", owner.getX()); ownerTag.putInt("y", owner.getY()); ownerTag.putInt("z", owner.getZ());
            context.put("ticketOwner", ownerTag);
        }
        Set<Long> desired = new HashSet<>();
        addWindow(desired, current); addWindow(desired, next); addWindow(desired, hq);
        Set<Long> previous = new HashSet<>();
        for (long value : context.getLongArray("forcedChunks")) previous.add(value);
        for (long value : previous) if (!desired.contains(value)) {
            ChunkPos pos = new ChunkPos(value);
            PARENT.forceChunk(level, owner, pos.x, pos.z, false, true);
        }
        for (long value : desired) if (!previous.contains(value)) {
            ChunkPos pos = new ChunkPos(value);
            PARENT.forceChunk(level, owner, pos.x, pos.z, true, true);
        }
        context.putLongArray("forcedChunks", desired.stream().mapToLong(Long::longValue).toArray());
        context.putInt("forcedChunkCount", desired.size());
        data.updateContext(context);
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)
            container.addForceLoadTicket(ship, SUBLEVEL, encounter.id().toString());
    }

    public static void release(ServerLevel level, EncounterSavedData data) {
        var encounter = data.encounter();
        var context = data.context();
        if (context.contains("ticketOwner")) {
            BlockPos owner = AssaultController.position(context.getCompound("ticketOwner"));
            for (long value : context.getLongArray("forcedChunks")) {
                ChunkPos pos = new ChunkPos(value);
                PARENT.forceChunk(level, owner, pos.x, pos.z, false, true);
            }
        }
        if (encounter != null && dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                instanceof ServerSubLevelContainer container) {
            for (var ship : container.getAllSubLevels()) if (encounter.owned().contains(ship.getUniqueId()))
                container.removeForceLoadTicket(ship, SUBLEVEL, encounter.id().toString());
        }
        context.putLongArray("forcedChunks", new long[0]);
        context.putInt("forcedChunkCount", 0);
        context.putBoolean("ticketsReleased", true);
        data.updateContext(context);
    }

    private static void addWindow(Set<Long> chunks, BlockPos center) {
        ChunkPos origin = new ChunkPos(center);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            chunks.add(ChunkPos.asLong(origin.x + x, origin.z + z));
    }
}
