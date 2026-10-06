package com.airship;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

public final class AirshipClient implements ClientModInitializer {
    /**
     * Descending has its own key (default C, rebindable) instead of the sprint key: the sprint key can
     * report "held" while toggled, which made the ship sink on its own.
     */
    private static final KeyMapping DESCEND = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.airship.descend",
                    InputConstants.KEY_C,
                    KeyMapping.Category.MISC
            )
    );

    /** Switches how see-through blocks of a flying ship are drawn (for comparing, see AirshipRenderMode). */
    private static final KeyMapping GLASS_MODE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.airship.glass_mode",
                    InputConstants.KEY_F8,
                    KeyMapping.Category.MISC
            )
    );

    /** Hold while thrusting to burn the turbo tank of the engines (5x fuel, much faster). */
    static final KeyMapping BOOST = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.airship.boost",
                    InputConstants.KEY_B,
                    KeyMapping.Category.MISC
            )
    );

    private static int lastSentFlags = -1;
    private static int tickCounter;

    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(ModBlockEntities.AIRSHIP_BUILD, AirshipBuildBlockRenderer::new);
        EntityRendererRegistry.register(ModEntities.AIRSHIP, AirshipEntityRenderer::new);
        AirshipGlassFeature.register();

        // Short explanations on the items ("tooltip.airship.<item>", more lines as ".2", ".3" ...).
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!AirshipMod.MOD_ID.equals(id.getNamespace())) {
                return;
            }
            String key = "tooltip.airship." + id.getPath();
            if (I18n.exists(key)) {
                lines.add(Component.translatable(key).withStyle(ChatFormatting.GRAY));
            }
            for (int i = 2; I18n.exists(key + "." + i); i++) {
                lines.add(Component.translatable(key + "." + i).withStyle(ChatFormatting.GRAY));
            }
        });
        AirshipHud.register();

        // Engine screen (a container menu) and Core screen (opened by the server's check result).
        MenuScreens.register(ModMenus.ENGINE, AirshipEngineScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(AirshipCoreInfoPayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() instanceof AirshipCoreScreen screen && screen.isFor(payload.pos())) {
                screen.update(payload);
            } else {
                client.gui.setScreen(new AirshipCoreScreen(payload));
            }
        });

        // Exact position and rotation of a ship, sent every tick while it moves.
        ClientPlayNetworking.registerGlobalReceiver(AirshipStatePayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null && client.level.getEntity(payload.entityId()) instanceof AirshipEntity ship) {
                ship.receiveSnapshot(payload);
            }
        });

        // Numbers for the flight display above the hotbar (sent to the pilot).
        ClientPlayNetworking.registerGlobalReceiver(AirshipHudPayload.TYPE, (payload, context) -> AirshipHud.update(payload));

        // Which passenger sits on which seat.
        ClientPlayNetworking.registerGlobalReceiver(AirshipSeatMapPayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.level != null && client.level.getEntity(payload.entityId()) instanceof AirshipEntity ship) {
                java.util.Map<Integer, Integer> assignment = new java.util.HashMap<>();
                for (int i = 0; i < payload.passengerIds().size() && i < payload.seatIndices().size(); i++) {
                    assignment.put(payload.passengerIds().get(i), payload.seatIndices().get(i));
                }
                ship.setSeatAssignment(assignment);
            }
        });

        // Block data of a ship: apply it now if the entity exists, otherwise park it until it does.
        ClientPlayNetworking.registerGlobalReceiver(AirshipBlocksPayload.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            Entity entity = client.level == null ? null : client.level.getEntity(payload.entityId());
            if (entity instanceof AirshipEntity ship) {
                ship.setClientData(payload);
            } else {
                AirshipPendingCells.put(payload.entityId(), payload);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AirshipPendingCells.clear();
            lastSentFlags = -1;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (GLASS_MODE.consumeClick()) {
                AirshipRenderMode.afterTerrain = !AirshipRenderMode.afterTerrain;
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.literal(AirshipRenderMode.afterTerrain
                            ? "Glas-Modus: nach dem Wasser gezeichnet"
                            : "Glas-Modus: normal gezeichnet"));
                }
            }
        });

        // While sitting in an airship, report the movement keys to the server.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !(client.player.getVehicle() instanceof AirshipEntity)) {
                lastSentFlags = -1;
                return;
            }

            int flags = 0;
            if (client.gui.screen() == null) {
                Options options = client.options;
                if (options.keyUp.isDown()) flags |= AirshipControlPayload.FORWARD;
                if (options.keyDown.isDown()) flags |= AirshipControlPayload.BACKWARD;
                if (options.keyLeft.isDown()) flags |= AirshipControlPayload.LEFT;
                if (options.keyRight.isDown()) flags |= AirshipControlPayload.RIGHT;
                if (options.keyJump.isDown()) flags |= AirshipControlPayload.UP;
                if (DESCEND.isDown()) flags |= AirshipControlPayload.DOWN;
                if (BOOST.isDown()) flags |= AirshipControlPayload.BOOST;
            }

            tickCounter++;
            // Send on change, plus a heartbeat so the server knows the keys are still held.
            if (flags != lastSentFlags || tickCounter % 5 == 0) {
                ClientPlayNetworking.send(new AirshipControlPayload(flags));
                lastSentFlags = flags;
            }
        });
    }
}
