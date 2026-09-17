package com.createdtr.defendtherealm.integration.sable;

import java.util.List;
import java.util.UUID;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import com.createdtr.defendtherealm.encounter.Encounter;
import com.createdtr.defendtherealm.persistence.EncounterSavedData;
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
    public static Cleanup removeOwned(ServerLevel level, EncounterSavedData data) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Vehicle removal requires server thread");
        Encounter encounter = data.encounter();
        if (encounter == null) return new Cleanup(0, 0);
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return new Cleanup(0, encounter.owned().size());

        // Removing a fragmented parent can synchronously expose another child.
        // Reconcile tags/lineage and remove in fixed-point passes so a fragment
        // created during this cleanup cannot escape the initial UUID snapshot.
        int removed = 0;
        boolean changed;
        do {
            changed = false;
            for (ServerSubLevel ship : List.copyOf(container.getAllSubLevels()))
                if (EncounterIntegrity.claimForCleanup(data, ship)) changed = true;
            for (UUID id : encounter.owned()) {
                var candidate = container.getSubLevel(id);
                if (!(candidate instanceof ServerSubLevel ship) || ship.isRemoved()) continue;
                com.createdtr.defendtherealm.integration.cbc.PrototypeWeapon.stop(
                        com.createdtr.defendtherealm.integration.cbc.PrototypeWeapon.find(ship));
                ship.getPlot().kickAllEntities();
                container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
                encounter.acknowledgeRemoval(id);
                removed++;
                changed = true;
            }
        } while (changed);
        return new Cleanup(removed, encounter.owned().size());
    }

    /** Acknowledge only UUIDs absent from both the live container and Sable's persisted ticket index. */
    public static int acknowledgeAuthoritativelyAbsent(ServerLevel level, Encounter encounter) {
        var value = SubLevelContainer.getContainer(level);
        if (!(value instanceof dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container)) return 0;
        int acknowledged = 0;
        for (UUID id : encounter.owned()) {
            if (container.getSubLevel(id) == null && !container.getAllTickets().containsKey(id)) {
                encounter.acknowledgeRemoval(id);
                acknowledged++;
            }
        }
        return acknowledged;
    }
}
