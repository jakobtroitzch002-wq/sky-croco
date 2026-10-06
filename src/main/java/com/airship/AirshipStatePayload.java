package com.airship;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: the exact position and rotation of a ship at a given server tick.
 * The client buffers these and plays them back evenly, which is smoother than vanilla entity packets
 * (those arrive at uneven times and round the rotation to 1.4 degree steps).
 */
public record AirshipStatePayload(int entityId, long tick, double x, double y, double z, float yaw)
        implements CustomPacketPayload {
    public static final Type<AirshipStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_state")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipStatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AirshipStatePayload::entityId,
            ByteBufCodecs.VAR_LONG, AirshipStatePayload::tick,
            ByteBufCodecs.DOUBLE, AirshipStatePayload::x,
            ByteBufCodecs.DOUBLE, AirshipStatePayload::y,
            ByteBufCodecs.DOUBLE, AirshipStatePayload::z,
            ByteBufCodecs.FLOAT, AirshipStatePayload::yaw,
            AirshipStatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
