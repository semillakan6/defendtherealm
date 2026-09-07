package com.createdtr.defendtherealm.integration.sable;

import java.util.List;
import java.util.UUID;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import com.createdtr.defendtherealm.encounter.Encounter;
import net.minecraft.server.level.ServerLevel;

/** Read-only server integration. Discovery never establishes encounter ownership. */
public final class SableVehicles {
    private SableVehicles() {}
    public record Vehicle(UUID id, String name, double x, double y, double z, UUID splitParent) {}
    public record Cleanup(int removed, int pending) {}

    public static List<Vehicle> inspect(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Vehicle discovery requires server thread");
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();
        return container.getAllSubLevels().stream().filter(ship -> !ship.isRemoved()).map(SableVehicles::describe).toList();
    }

    private static Vehicle describe(ServerSubLevel ship) {
        var position = ship.logicalPose().position();
        return new Vehicle(ship.getUniqueId(), ship.getName(), position.x(), position.y(), position.z(), ship.getSplitFromSubLevel());
    }

    /** Removes only UUIDs already owned by the encounter; unknown/unloaded UUIDs remain pending. */
    public static Cleanup removeOwned(ServerLevel level, Encounter encounter) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Vehicle removal requires server thread");
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return new Cleanup(0, encounter.owned().size());

        // Capture all currently loaded descendants before removing any parent.
        boolean changed;
        do {
            changed = false;
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                UUID parent = ship.getSplitFromSubLevel();
                if (parent != null && encounter.inherit(ship.getUniqueId(), parent)) changed = true;
            }
        } while (changed);

        int removed = 0;
        for (UUID id : encounter.owned()) {
            var candidate = container.getSubLevel(id);
            if (!(candidate instanceof ServerSubLevel ship) || ship.isRemoved()) continue;
            container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            encounter.acknowledgeRemoval(id);
            removed++;
        }
        return new Cleanup(removed, encounter.owned().size());
    }
}
