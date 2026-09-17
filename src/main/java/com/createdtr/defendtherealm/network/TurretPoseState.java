package com.createdtr.defendtherealm.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Side-neutral packet sink; client-only code resolves Sable and CBC objects. */
public final class TurretPoseState {
    private static final Map<UUID, TurretPosePayload> LATEST = new ConcurrentHashMap<>();
    private TurretPoseState() {}

    public static void receive(TurretPosePayload payload) {
        LATEST.compute(payload.vehicle(), (id, current) -> current == null
                || payload.serverTick() >= current.serverTick() ? payload : current);
    }

    public static List<TurretPosePayload> latest() { return List.copyOf(LATEST.values()); }
    public static void remove(UUID vehicle) { LATEST.remove(vehicle); }
    public static void clear() { LATEST.clear(); }
}
