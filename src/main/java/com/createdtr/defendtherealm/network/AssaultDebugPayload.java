package com.createdtr.defendtherealm.network;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AssaultDebugPayload(UUID encounter, String dimension, String phase, String targetType,
        long serverTime, double shipX, double shipY, double shipZ, double targetX, double targetY, double targetZ,
        long[] route, int cursor, boolean hasOrbit, long orbit, boolean hasBlocker, long blocker)
        implements CustomPacketPayload {
    public static final Type<AssaultDebugPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CreateDefendtheRealm.MODID, "assault_debug"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AssaultDebugPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> payload.write(buffer), AssaultDebugPayload::read);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounter); buffer.writeUtf(dimension); buffer.writeUtf(phase); buffer.writeUtf(targetType);
        buffer.writeLong(serverTime); buffer.writeDouble(shipX); buffer.writeDouble(shipY); buffer.writeDouble(shipZ);
        buffer.writeDouble(targetX); buffer.writeDouble(targetY); buffer.writeDouble(targetZ);
        buffer.writeLongArray(route); buffer.writeVarInt(cursor);
        buffer.writeBoolean(hasOrbit); if (hasOrbit) buffer.writeLong(orbit);
        buffer.writeBoolean(hasBlocker); if (hasBlocker) buffer.writeLong(blocker);
    }
    private static AssaultDebugPayload read(RegistryFriendlyByteBuf buffer) {
        UUID encounter = buffer.readUUID(); String dimension = buffer.readUtf(); String phase = buffer.readUtf();
        String targetType = buffer.readUtf(); long time = buffer.readLong();
        double sx = buffer.readDouble(), sy = buffer.readDouble(), sz = buffer.readDouble();
        double tx = buffer.readDouble(), ty = buffer.readDouble(), tz = buffer.readDouble();
        long[] route = buffer.readLongArray(); int cursor = buffer.readVarInt();
        boolean hasOrbit = buffer.readBoolean(); long orbit = hasOrbit ? buffer.readLong() : 0;
        boolean hasBlocker = buffer.readBoolean(); long blocker = hasBlocker ? buffer.readLong() : 0;
        return new AssaultDebugPayload(encounter, dimension, phase, targetType, time, sx, sy, sz, tx, ty, tz,
                route, cursor, hasOrbit, orbit, hasBlocker, blocker);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
