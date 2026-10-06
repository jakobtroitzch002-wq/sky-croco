package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client: the result of checking a ship from its Core (shown on the Core screen). */
public record AirshipCoreInfoPayload(
        BlockPos pos,
        boolean ready,
        String problem,
        int blocks,
        int maxBlocks,
        int balloons,
        int balloonsRequired,
        int seatBlocks,
        int cushions,
        int engines,
        int fuelTicks,
        int maxFuelTicks,
        int animals
) implements CustomPacketPayload {
    public static final Type<AirshipCoreInfoPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_core_info")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipCoreInfoPayload> CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, AirshipCoreInfoPayload>() {
                @Override
                public AirshipCoreInfoPayload decode(RegistryFriendlyByteBuf buf) {
                    return new AirshipCoreInfoPayload(
                            buf.readBlockPos(), buf.readBoolean(), buf.readUtf(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, AirshipCoreInfoPayload payload) {
                    buf.writeBlockPos(payload.pos());
                    buf.writeBoolean(payload.ready());
                    buf.writeUtf(payload.problem());
                    buf.writeVarInt(payload.blocks());
                    buf.writeVarInt(payload.maxBlocks());
                    buf.writeVarInt(payload.balloons());
                    buf.writeVarInt(payload.balloonsRequired());
                    buf.writeVarInt(payload.seatBlocks());
                    buf.writeVarInt(payload.cushions());
                    buf.writeVarInt(payload.engines());
                    buf.writeVarInt(payload.fuelTicks());
                    buf.writeVarInt(payload.maxFuelTicks());
                    buf.writeVarInt(payload.animals());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
