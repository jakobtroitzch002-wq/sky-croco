package com.airship;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: a button on the Core screen was pressed. */
public record AirshipCoreActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
    public static final int RECHECK = 0;
    public static final int FORGET_TERRAIN = 1;

    public static final Type<AirshipCoreActionPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_core_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipCoreActionPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, AirshipCoreActionPayload::pos,
            ByteBufCodecs.VAR_INT, AirshipCoreActionPayload::action,
            AirshipCoreActionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
