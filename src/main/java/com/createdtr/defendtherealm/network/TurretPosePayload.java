package com.createdtr.defendtherealm.network;

import com.createdtr.defendtherealm.CreateDefendtheRealm;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TurretPosePayload(String dimension, UUID vehicle, long serverTick, List<Pose> poses)
        implements CustomPacketPayload {
    public record Pose(long mountPosition, int entityId, UUID entityUuid, float yaw, float pitch) {}
    public static final Type<TurretPosePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CreateDefendtheRealm.MODID, "turret_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TurretPosePayload> CODEC = StreamCodec.of(
            TurretPosePayload::write, TurretPosePayload::read);

    public TurretPosePayload {
        poses = List.copyOf(poses);
        if (poses.size() > 32) throw new IllegalArgumentException("Too many turret poses");
    }

    private static void write(RegistryFriendlyByteBuf buffer, TurretPosePayload payload) {
        buffer.writeUtf(payload.dimension);
        buffer.writeUUID(payload.vehicle);
        buffer.writeLong(payload.serverTick);
        buffer.writeVarInt(payload.poses.size());
        for (Pose pose : payload.poses) {
            buffer.writeLong(pose.mountPosition);
            buffer.writeVarInt(pose.entityId);
            buffer.writeUUID(pose.entityUuid);
            buffer.writeFloat(pose.yaw);
            buffer.writeFloat(pose.pitch);
        }
    }

    private static TurretPosePayload read(RegistryFriendlyByteBuf buffer) {
        String dimension = buffer.readUtf();
        UUID vehicle = buffer.readUUID();
        long serverTick = buffer.readLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid turret pose count");
        List<Pose> poses = new ArrayList<>(count);
        for (int i = 0; i < count; i++) poses.add(new Pose(buffer.readLong(), buffer.readVarInt(),
                buffer.readUUID(), buffer.readFloat(), buffer.readFloat()));
        return new TurretPosePayload(dimension, vehicle, serverTick, poses);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
