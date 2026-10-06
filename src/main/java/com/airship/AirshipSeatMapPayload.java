package com.airship;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: which passenger sits on which seat of a ship (passenger entity id -> seat index).
 * Seat 0 is the control seat. A fixed assignment means nobody jumps to another seat when someone leaves.
 */
public record AirshipSeatMapPayload(int entityId, List<Integer> passengerIds, List<Integer> seatIndices)
        implements CustomPacketPayload {
    public static final Type<AirshipSeatMapPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_seat_map")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipSeatMapPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AirshipSeatMapPayload::entityId,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), AirshipSeatMapPayload::passengerIds,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), AirshipSeatMapPayload::seatIndices,
            AirshipSeatMapPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
