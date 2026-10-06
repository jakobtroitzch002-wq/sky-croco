package com.airship;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AirshipMod implements ModInitializer {
    public static final String MOD_ID = "airship";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ModBlocks.initialize();
        FlammableBlockRegistry.getDefaultInstance().add(ModBlocks.AIRSHIP_BALLOON, 30, 60);
        FlammableBlockRegistry.getDefaultInstance().add(ModBlocks.AIRSHIP_SEAT, 5, 20);
        ModBlockEntities.initialize();
        ModEntities.initialize();
        ModMenus.initialize();

        PayloadTypeRegistry.serverboundPlay().register(
                AirshipControlPayload.TYPE,
                AirshipControlPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AirshipBlocksPayload.TYPE,
                AirshipBlocksPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AirshipStatePayload.TYPE,
                AirshipStatePayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AirshipSeatMapPayload.TYPE,
                AirshipSeatMapPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AirshipHudPayload.TYPE,
                AirshipHudPayload.CODEC
        );
        PayloadTypeRegistry.clientboundPlay().register(
                AirshipCoreInfoPayload.TYPE,
                AirshipCoreInfoPayload.CODEC
        );
        PayloadTypeRegistry.serverboundPlay().register(
                AirshipCoreActionPayload.TYPE,
                AirshipCoreActionPayload.CODEC
        );
        AirshipControls.registerServer();

        // Buttons on the Core screen.
        ServerPlayNetworking.registerGlobalReceiver(AirshipCoreActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = payload.pos();
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0
                    || !level.hasChunkAt(pos)
                    || !level.getBlockState(pos).is(ModBlocks.AIRSHIP_CORE)) {
                return;
            }
            if (payload.action() == AirshipCoreActionPayload.FORGET_TERRAIN
                    && level.getBlockEntity(pos) instanceof AirshipCoreBlockEntity core) {
                core.setTerrain(java.util.List.of());
            }
            AirshipAssembler.sendInspection(level, pos, player);
        });

        // Send the ship's blocks to every player that starts seeing the ship.
        EntityTrackingEvents.START_TRACKING.register((trackedEntity, player) -> {
            if (trackedEntity instanceof AirshipEntity ship) {
                ServerPlayNetworking.send(player, new AirshipBlocksPayload(ship.getId(), ship.clientCells(), ship.getClientCushions(), ship.getSeats()));
                ServerPlayNetworking.send(player, ship.statePayload(player.level().getGameTime()));
                ServerPlayNetworking.send(player, ship.seatMapPayload());
            }
        });

        LOGGER.info("Airship mod initialized");
    }
}
