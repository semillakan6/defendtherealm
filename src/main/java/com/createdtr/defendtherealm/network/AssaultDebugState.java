package com.createdtr.defendtherealm.network;

/** Side-neutral packet sink; only the client renderer reads it. */
public final class AssaultDebugState {
    private static volatile AssaultDebugPayload latest;
    private AssaultDebugState() {}
    public static void receive(AssaultDebugPayload payload) { latest = payload; }
    public static AssaultDebugPayload latest() { return latest; }
    public static void clear() { latest = null; }
}
