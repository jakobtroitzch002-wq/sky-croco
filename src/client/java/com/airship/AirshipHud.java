package com.airship;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The flight display above the hotbar: speed, fuel left, and the turbo tank. Only shown to the pilot
 * while flying (the server sends the numbers a few times per second).
 */
final class AirshipHud {
    private static final int WIDTH = 110;
    private static final int BACKGROUND = 0xE02A1C11;
    private static final int TEXT = 0xFFEADBB5;
    private static final int MUTED = 0xFFB79F6E;
    private static final int TRACK = 0xFF000000 | 0x4A3020;

    private static AirshipHudPayload latest;
    private static long receivedAt;

    private AirshipHud() {}

    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "flight_display"), AirshipHud::render);
    }

    static void update(AirshipHudPayload payload) {
        latest = payload;
        receivedAt = System.currentTimeMillis();
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        AirshipHudPayload data = latest;
        if (data == null || client.player == null || !(client.player.getVehicle() instanceof AirshipEntity)) {
            return;
        }
        if (System.currentTimeMillis() - receivedAt > 2000) {
            return;
        }
        Font font = client.font;

        boolean showTurbo = data.turboTicks() > 0 || data.boosting();
        int height = showTurbo ? 38 : 27;
        // Right above the hearts (left edge of the hotbar); one row higher if the armor bar is shown there.
        int armorLift = client.player.getArmorValue() > 0 ? 10 : 0;
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 41 - armorLift - height;

        g.fill(x, y, x + WIDTH, y + height, AirshipGuiStyle.BRASS);
        g.fill(x + 1, y + 1, x + WIDTH - 1, y + height - 1, BACKGROUND);

        // Speed and engines
        String speed = String.format("%.1f", data.speed()) + " b/s";
        g.text(font, Component.literal(speed).withStyle(ChatFormatting.BOLD), x + 4, y + 3, TEXT, false);
        String engines = data.activeEngines() + "/" + data.engines() + " Antr.";
        g.text(font, engines, x + WIDTH - 4 - font.width(engines), y + 3, MUTED, false);

        int timeX = x + 15;
        int barX = timeX + font.width("20:00") + 3;
        int barWidth = x + WIDTH - 4 - barX;

        // Fuel: flame, time left, bar
        AirshipGuiStyle.flame(g, x + 4, y + 14);
        g.text(font, Component.literal(AirshipFuel.time(data.fuelTicks())).withStyle(ChatFormatting.BOLD),
                timeX, y + 15, data.fuelTicks() > 0 ? TEXT : AirshipGuiStyle.BAD, false);
        bar(g, barX, y + 17, barWidth, (float) data.fuelTicks() / AirshipEngineBlockEntity.MAX_FUEL,
                AirshipGuiStyle.FLAME);

        // Turbo: T, time left (at turbo speed of burning), bar
        if (showTurbo) {
            g.text(font, Component.literal("T").withStyle(ChatFormatting.BOLD), x + 6, y + 26,
                    data.boosting() ? AirshipGuiStyle.EMBER : AirshipGuiStyle.TURBO, false);
            g.text(font, Component.literal(AirshipFuel.time(data.turboTicks() / AirshipEntity.TURBO_BURN_RATE))
                            .withStyle(ChatFormatting.BOLD),
                    timeX, y + 26, data.boosting() ? AirshipGuiStyle.EMBER : TEXT, false);
            bar(g, barX, y + 28, barWidth, (float) data.turboTicks() / AirshipEngineBlockEntity.MAX_TURBO,
                    AirshipGuiStyle.TURBO);
        }
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, float fraction, int color) {
        g.fill(x, y, x + w, y + 4, TRACK);
        int filled = Math.round(w * Math.max(0.0F, Math.min(1.0F, fraction)));
        if (fraction > 0.0F && filled < 1) {
            filled = 1;
        }
        if (filled > 0) {
            g.fill(x, y, x + filled, y + 4, color);
        }
    }
}
