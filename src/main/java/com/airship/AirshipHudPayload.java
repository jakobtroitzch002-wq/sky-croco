package com.airship;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> pilot: what the flight display above the hotbar shows.
 *
 * @param fuelTicks    fuel left in the fullest engine (thrust ticks)
 * @param turboTicks   turbo fuel left in the fullest engine
 * @param engines      number of engines
 * @param activeEngines engines currently pushing
 * @param speed        speed in blocks per second
 * @param boosting     whether the turbo is burning right now
 */
public record AirshipHudPayload(int fuelTicks, int turboTicks, int engines, int activeEngines, float speed, boolean boosting)
        implements CustomPacketPayload {
    public static final Type<AirshipHudPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_hud")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AirshipHudPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AirshipHudPayload::fuelTicks,
            ByteBufCodecs.VAR_INT, AirshipHudPayload::turboTicks,
            ByteBufCodecs.VAR_INT, AirshipHudPayload::engines,
            ByteBufCodecs.VAR_INT, AirshipHudPayload::activeEngines,
            ByteBufCodecs.FLOAT, AirshipHudPayload::speed,
            ByteBufCodecs.BOOL, AirshipHudPayload::boosting,
            AirshipHudPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
