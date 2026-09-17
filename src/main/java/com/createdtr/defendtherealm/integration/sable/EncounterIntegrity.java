package com.createdtr.defendtherealm.integration.sable;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Bridges physical Sable block identity and lineage to the persisted encounter. */
@EventBusSubscriber(modid = CreateDefendtheRealm.MODID)
public final class EncounterIntegrity {
    private static final String OFFSETS = "integrityOffsets";
    private static final String ENCOUNTER_TAG = "createdefendtherealmEncounter";

    private EncounterIntegrity() {}

    public static boolean belongsTo(Encounter encounter, ServerSubLevel ship) {
        CompoundTag user = ship.getUserDataTag();
        return user != null && user.hasUUID(ENCOUNTER_TAG)
                && encounter.id().equals(user.getUUID(ENCOUNTER_TAG));
    }

    /** Cleanup-only recovery for descendants observed after their parent changed or vanished. */
    public static boolean claimForCleanup(EncounterSavedData data, ServerSubLevel ship) {
        Encounter encounter = data.encounter();
        if (encounter == null || ship.isRemoved()) return false;
        boolean tagged = belongsTo(encounter, ship);
        UUID parent = ship.getSplitFromSubLevel();
        if (!tagged && (parent == null || !encounter.owned().contains(parent))) return false;
        boolean added = !encounter.owned().contains(ship.getUniqueId());
        encounter.own(ship.getUniqueId());
        if (!tagged) {
            CompoundTag user = ship.getUserDataTag() == null ? new CompoundTag() : ship.getUserDataTag().copy();
            user.putUUID(ENCOUNTER_TAG, encounter.id());
            ship.setUserDataTag(user);
        }
        if (added) data.setDirty();
        return added;
    }

    @SubscribeEvent
    public static void serverStarting(ServerStartingEvent event) {
        EncounterSavedData data = EncounterSavedData.get(event.getServer());
        if (data.encounter() == null || data.encounter().state() == Encounter.State.COMPLETED) return;
        CompoundTag context = data.context();
        context.putBoolean("sableReconciled", false);
        context.putBoolean("recoveryPending", true);
        context.remove("recoveryStartedTick");
        context.remove("controllerFailure");
        data.updateContext(context);
        for (ServerLevel level : event.getServer().getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) reconcile(level, container);
        }
    }

    public static void initializeRoot(EncounterSavedData data, ServerSubLevel ship) {
        Encounter encounter = data.encounter();
        if (encounter == null || encounter.weights().isEmpty()) return;
        int sourceMinX = Integer.MAX_VALUE, sourceMinY = Integer.MAX_VALUE, sourceMinZ = Integer.MAX_VALUE;
        for (long packed : encounter.weights().keySet()) {
            BlockPos pos = BlockPos.of(packed);
            sourceMinX = Math.min(sourceMinX, pos.getX());
            sourceMinY = Math.min(sourceMinY, pos.getY());
            sourceMinZ = Math.min(sourceMinZ, pos.getZ());
        }
        var bounds = ship.getPlot().getBoundingBox();
        putOffset(data, ship.getUniqueId(), bounds.minX() - sourceMinX,
                bounds.minY() - sourceMinY, bounds.minZ() - sourceMinZ);
    }

    /** Called by the pinned Sable split hook before its tracking system clears lineage. */
    public static void inherit(ServerSubLevel child, ServerSubLevel parent) {
        ServerLevel level = child.getLevel();
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        Encounter encounter = data.encounter();
        if (encounter == null || !encounter.inherit(child.getUniqueId(), parent.getUniqueId())) return;

        CompoundTag user = child.getUserDataTag() == null ? new CompoundTag() : child.getUserDataTag().copy();
        user.putUUID(ENCOUNTER_TAG, encounter.id());
        child.setUserDataTag(user);

        BlockPos parentOffset = offset(data.context(), parent.getUniqueId());
        BlockPos parentCenter = parent.getPlot().getCenterBlock();
        BlockPos childCenter = child.getPlot().getCenterBlock();
        putOffset(data, child.getUniqueId(),
                parentOffset.getX() + childCenter.getX() - parentCenter.getX(),
                parentOffset.getY() + childCenter.getY() - parentCenter.getY(),
                parentOffset.getZ() + childCenter.getZ() - parentCenter.getZ());
        CreateDefendtheRealm.LOGGER.info("Encounter {} inherited split {} from {}",
                encounter.id(), child.getUniqueId(), parent.getUniqueId());
    }

    /** Invoked at the head of Sable's authoritative old/new block-state callback. */
    public static void blockChanged(ServerLevel level, LevelChunk chunk, int x, int y, int z,
            BlockState oldState, BlockState newState) {
        if (oldState.isAir() || oldState.getBlock() == newState.getBlock()) return;
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        Encounter encounter = data.encounter();
        if (encounter == null || encounter.state() != Encounter.State.APPROACHING
                && encounter.state() != Encounter.State.ENGAGING) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        var plot = container.getPlot(chunk.getPos());
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel ship)
                || !encounter.owned().contains(ship.getUniqueId())) return;
        BlockPos physical = new BlockPos(x, y, z);
        BlockPos canonical = canonical(physical, offset(data.context(), ship.getUniqueId()));
        if (encounter.recordLoss(canonical.asLong())) {
            CompoundTag context = data.context();
            context.putLong("lastDamageTick", level.getGameTime());
            context.putUUID("lastDamagedVehicle", ship.getUniqueId());
            context.putLong("lastDamagedCanonicalBlock", canonical.asLong());
            if (encounter.state() == Encounter.State.DESTROYING && !context.contains("defeatTick")) {
                context.putLong("defeatTick", level.getGameTime());
            }
            data.updateContext(context);
        }
    }

    @SubscribeEvent
    public static void containerReady(ForgeSableSubLevelContainerReadyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        event.getContainer().addObserver(new Observer(level));
        // Sable publishes this from the ServerLevel constructor, before
        // ServerLevel#getServer is necessarily attached. ServerStartingEvent
        // performs SavedData reconciliation after all levels are constructed.
        if (level.getServer() != null) reconcile(level, event.getContainer());
    }

    private static void reconcile(ServerLevel level, SubLevelContainer container) {
        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        Encounter encounter = data.encounter();
        if (encounter == null || encounter.state() == Encounter.State.COMPLETED) return;
        for (SubLevel value : container.getAllSubLevels()) {
            if (!(value instanceof ServerSubLevel ship) || ship.isRemoved()) continue;
            CompoundTag user = ship.getUserDataTag();
            if (user != null && user.hasUUID(ENCOUNTER_TAG)
                    && encounter.id().equals(user.getUUID(ENCOUNTER_TAG))) encounter.own(ship.getUniqueId());
        }
        CompoundTag context = data.context();
        context.putBoolean("sableReconciled", true);
        context.putLong("sableReconciledTick", level.getGameTime());
        data.updateContext(context);
    }

    private static void putOffset(EncounterSavedData data, UUID id, int x, int y, int z) {
        CompoundTag context = data.context();
        ListTag values = context.getList(OFFSETS, Tag.TAG_COMPOUND);
        values.removeIf(value -> ((CompoundTag) value).getUUID("id").equals(id));
        CompoundTag entry = new CompoundTag();
        entry.putUUID("id", id); entry.putInt("x", x); entry.putInt("y", y); entry.putInt("z", z);
        values.add(entry); context.put(OFFSETS, values); data.updateContext(context);
    }

    private static BlockPos offset(CompoundTag context, UUID id) {
        for (Tag value : context.getList(OFFSETS, Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) value;
            if (entry.hasUUID("id") && entry.getUUID("id").equals(id))
                return new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
        }
        return BlockPos.ZERO;
    }

    public static BlockPos canonical(BlockPos physical, BlockPos physicalMinusCanonical) {
        return physical.subtract(physicalMinusCanonical);
    }

    public static BlockPos physical(CompoundTag context, UUID vehicle, BlockPos canonical) {
        return canonical.offset(offset(context, vehicle));
    }

    private static final class Observer implements SubLevelObserver {
        private final ServerLevel level;
        private Observer(ServerLevel level) { this.level = level; }

        @Override public void onSubLevelAdded(SubLevel subLevel) {
            if (level.getServer() == null) return;
            if (!(subLevel instanceof ServerSubLevel ship)) return;
            CompoundTag user = ship.getUserDataTag();
            if (user == null || !user.hasUUID(ENCOUNTER_TAG)) return;
            EncounterSavedData data = EncounterSavedData.get(level.getServer());
            Encounter encounter = data.encounter();
            if (encounter != null && encounter.id().equals(user.getUUID(ENCOUNTER_TAG))) {
                encounter.own(ship.getUniqueId());
                data.setDirty();
            }
        }

        @Override public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
            if (level.getServer() == null) return;
            if (reason != SubLevelRemovalReason.REMOVED) return;
            EncounterSavedData data = EncounterSavedData.get(level.getServer());
            Encounter encounter = data.encounter();
            if (encounter != null && encounter.owned().contains(subLevel.getUniqueId())) {
                encounter.acknowledgeRemoval(subLevel.getUniqueId());
                data.setDirty();
            }
        }
    }
}
