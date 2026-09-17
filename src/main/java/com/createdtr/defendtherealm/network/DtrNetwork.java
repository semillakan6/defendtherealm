package com.createdtr.defendtherealm.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class DtrNetwork {
    private DtrNetwork() {}
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(AssaultDebugPayload.TYPE, AssaultDebugPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> AssaultDebugState.receive(payload)))
                .playToClient(TurretPosePayload.TYPE, TurretPosePayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> TurretPoseState.receive(payload)));
    }
}
