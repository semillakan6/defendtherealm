package com.createdtr.defendtherealm.integration.toolgun;

import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.integration.cbc.CbcAmmunitionSnapshot;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
import com.enxv.aeronauticsstructuretool.PlacementSnapMode;
import com.enxv.aeronauticsstructuretool.blueprint.codec.BlueprintArchiveCodec;
import com.enxv.aeronauticsstructuretool.blueprint.codec.NativeBlueprintFormat;
import com.enxv.aeronauticsstructuretool.blueprint.codec.NativeBlueprintReader;
import com.enxv.aeronauticsstructuretool.blueprint.geometry.PlotBlockDataReader;
import com.enxv.aeronauticsstructuretool.blueprint.model.NativeBlueprintDocument;
import com.enxv.aeronauticsstructuretool.blueprint.placement.BlueprintVerticalPlacement;
import com.enxv.aeronauticsstructuretool.blueprint.placement.NativeBlueprintPlacementService;
import com.enxv.aeronauticsstructuretool.blueprint.placement.PlacementTargetMath;
import com.enxv.aeronauticsstructuretool.blueprint.runtime.BlueprintPlacementObserver;
import com.enxv.aeronauticsstructuretool.blueprint.storage.BlueprintFileRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Optional development-only bridge to the Toolgun's compiled placement API.
 * Callers must guard class loading with the Toolgun mod-presence check.
 */
public final class ToolgunPrototypeSpawner {
    private static final int MAX_BLOCKS = 4096;
    private static final int RUNTIME_CLEARANCE = 4;
    private static final long MIN_AMMUNITION = 20;

    private ToolgunPrototypeSpawner() {}

    public record Result(UUID encounterId, UUID vehicleId, int blocks, long ammunition,
                         boolean machineryReady) {}

    public static Result spawn(ServerPlayer player, String templateName, BlockPos spawn, BlockPos target)
            throws IOException {
        ServerLevel level = player.serverLevel();
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Prototype spawn requires server thread");

        EncounterSavedData data = EncounterSavedData.get(level.getServer());
        Encounter previous = data.encounter();
        if (previous != null && previous.state() != Encounter.State.COMPLETED) {
            throw new IllegalArgumentException("An encounter is already active; inspect or cancel it first");
        }

        Path directory = BlueprintFileRepository.serverDirectory(
                level.getServer().getWorldPath(LevelResource.ROOT), player.getUUID());
        byte[] archive = BlueprintFileRepository.read(directory, templateName);
        NativeBlueprintDocument document = NativeBlueprintReader.read(
                BlueprintArchiveCodec.decodeCompressedOrRaw(archive));
        ValidatedBlueprint validated = validate(level, document, spawn);

        Encounter encounter = new Encounter(UUID.randomUUID(), validated.weights());
        CompoundTag context = context(player, templateName, level, spawn, target);
        data.begin(encounter, context);
        encounter.advance(Encounter.State.SPAWNING);
        data.setDirty();

        BlueprintPlacementObserver observer = new BlueprintPlacementObserver() {
            @Override
            public void onCompleted() {
                if (encounter.state() == Encounter.State.SPAWNING) encounter.advance(Encounter.State.APPROACHING);
                data.setDirty();
            }

            @Override
            public void onFailed(String message) {
                context.putString("restoreFailure", message == null ? "unknown restoration failure" : message);
                data.updateContext(context);
                encounter.terminate(Encounter.Reason.WEAPON_FAILURE);
                if (encounter.owned().isEmpty()) encounter.advance(Encounter.State.COMPLETED);
                data.setDirty();
            }
        };

        try {
            var result = NativeBlueprintPlacementService.place(level, spawn, Direction.UP, templateName, archive,
                    spawn.getX() + 0.5D, spawn.getY() + 0.5D, spawn.getZ() + 0.5D,
                    0, 100, 0, 0, 0, PlacementSnapMode.HIT, null,
                    BlueprintVerticalPlacement.unchanged(), player.getUUID(), observer);
            if (result.rootSubLevel() == null) throw new IOException("Toolgun did not return a root vehicle");

            UUID vehicleId = result.rootSubLevel().getUniqueId();
            encounter.own(vehicleId);
            CompoundTag currentVehicleData = result.rootSubLevel().getUserDataTag();
            CompoundTag vehicleData = currentVehicleData == null ? new CompoundTag() : currentVehicleData.copy();
            vehicleData.putUUID("createdefendtherealmEncounter", encounter.id());
            vehicleData.putString("createdefendtherealmTemplate", BlueprintFileRepository.normalizeName(templateName));
            result.rootSubLevel().setUserDataTag(vehicleData);
            context.putUUID("rootVehicle", vehicleId);
            context.putInt("placedSublevels", result.placedSubLevelCount());
            context.putDouble("placedMass", result.placedTotalMass());
            data.updateContext(context);

            if (result.runtimeRestoredImmediately() && encounter.state() == Encounter.State.SPAWNING) {
                encounter.advance(Encounter.State.APPROACHING);
                data.setDirty();
            }
            return new Result(encounter.id(), vehicleId, validated.weights().size(), validated.ammunition(),
                    encounter.state() == Encounter.State.APPROACHING);
        } catch (IOException | RuntimeException ex) {
            encounter.terminate(Encounter.Reason.INVALID_PLACEMENT);
            if (encounter.owned().isEmpty()) encounter.advance(Encounter.State.COMPLETED);
            context.putString("placementFailure", safeMessage(ex));
            data.updateContext(context);
            throw ex;
        }
    }

    private static ValidatedBlueprint validate(ServerLevel level, NativeBlueprintDocument document, BlockPos spawn)
            throws IOException {
        if (!NativeBlueprintFormat.isSupported(document.format())) {
            throw new IOException("Unsupported Toolgun blueprint format: " + document.format());
        }
        if (document.sublevels().size() != 1) {
            throw new IOException("Milestone 01 requires exactly one Sable sublevel");
        }
        var saved = document.sublevels().getFirst();
        var blocks = PlotBlockDataReader.read(saved.plotTag(), saved.sourceMinBuildHeight());
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IOException("Prototype block count must be between 1 and " + MAX_BLOCKS);
        }

        var cannons = saved.runtimeContraptions().stream().filter(value -> "cbc_pitch".equals(value.kind())).toList();
        if (cannons.size() != 1) throw new IOException("Prototype requires exactly one assembled CBC pitch contraption");
        long ammunition;
        try {
            ammunition = CbcAmmunitionSnapshot.finiteAutocannonCartridges(cannons.getFirst().contraptionTag());
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        if (ammunition < MIN_AMMUNITION) {
            throw new IOException("Prototype requires at least " + MIN_AMMUNITION + " finite autocannon cartridges");
        }

        Map<Long, Integer> weights = new LinkedHashMap<>();
        for (var block : blocks) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block.state().getBlock());
            int weight = isFunctional(id) ? 3 : 1;
            if (weights.put(block.blockPos().asLong(), weight) != null) {
                throw new IOException("Blueprint contains a duplicate integrity position");
            }
        }
        validateClearance(level, document, saved.localAnchor(), blocks, spawn);
        return new ValidatedBlueprint(Map.copyOf(weights), ammunition);
    }

    private static boolean isFunctional(ResourceLocation id) {
        String value = id.toString();
        return value.equals("aeronautics:adjustable_burner")
                || value.equals("aeronautics:throttle_lever")
                || value.equals("create:analog_lever")
                || value.equals("createbigcannons:cannon_mount");
    }

    private static void validateClearance(ServerLevel level, NativeBlueprintDocument document, Vector3d localAnchor,
                                          java.util.List<PlotBlockDataReader.PlotBlock> blocks, BlockPos spawn)
            throws IOException {
        Vector3d target = PlacementTargetMath.computePlacementTarget(spawn, Direction.UP,
                spawn.getX() + 0.5D, spawn.getY() + 0.5D, spawn.getZ() + 0.5D,
                PlacementSnapMode.HIT, 0, 0, 0);
        Quaterniond orientation = new Quaterniond(document.rootOrientation())
                .mul(PlacementTargetMath.computeExtraRotation(Direction.UP, 0)).normalize();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (var block : blocks) {
            Vector3d position = new Vector3d(block.center()).sub(localAnchor);
            orientation.transform(position);
            position.add(target);
            minX = Math.min(minX, position.x); minY = Math.min(minY, position.y); minZ = Math.min(minZ, position.z);
            maxX = Math.max(maxX, position.x); maxY = Math.max(maxY, position.y); maxZ = Math.max(maxZ, position.z);
        }
        BlockPos min = BlockPos.containing(minX - RUNTIME_CLEARANCE, minY - RUNTIME_CLEARANCE, minZ - RUNTIME_CLEARANCE);
        BlockPos max = BlockPos.containing(maxX + RUNTIME_CLEARANCE, maxY + RUNTIME_CLEARANCE, maxZ + RUNTIME_CLEARANCE);
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) {
            throw new IOException("Prototype clearance volume is outside the dimension build height");
        }
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.getWorldBorder().isWithinBounds(pos)) throw new IOException("Prototype clearance crosses the world border");
            if (!level.hasChunkAt(pos)) throw new IOException("Load every chunk in the prototype clearance volume first");
            if (!level.getBlockState(pos).isAir() || !level.getFluidState(pos).isEmpty()) {
                throw new IOException("Prototype clearance is obstructed at " + pos.toShortString());
            }
        }
    }

    private static CompoundTag context(ServerPlayer player, String templateName, ServerLevel level,
                                       BlockPos spawn, BlockPos target) {
        CompoundTag context = new CompoundTag();
        context.putUUID("owner", player.getUUID());
        context.putString("template", BlueprintFileRepository.normalizeName(templateName));
        context.putString("dimension", level.dimension().location().toString());
        putPos(context, "spawn", spawn);
        putPos(context, "target", target);
        return context;
    }

    private static void putPos(CompoundTag tag, String key, BlockPos pos) {
        CompoundTag value = new CompoundTag();
        value.putInt("x", pos.getX()); value.putInt("y", pos.getY()); value.putInt("z", pos.getZ());
        tag.put(key, value);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record ValidatedBlueprint(Map<Long, Integer> weights, long ammunition) {}
}
