package com.airship;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server: which control keys the pilot currently holds (bit flags). */
public record AirshipControlPayload(int flags) implements CustomPacketPayload {
    public static final Type<AirshipControlPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_control")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipControlPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            AirshipControlPayload::flags,
            AirshipControlPayload::new
    );

    public static final int FORWARD = 1;
    public static final int BACKWARD = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int UP = 16;
    public static final int DOWN = 32;
    public static final int BOOST = 64;
    public static final int ALL = FORWARD | BACKWARD | LEFT | RIGHT | UP | DOWN | BOOST;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
