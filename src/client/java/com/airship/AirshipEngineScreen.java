package com.airship;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The engine screen: fuel slot, fuel gauge, speed bonus, and the player's inventory. */
public class AirshipEngineScreen extends AbstractContainerScreen<AirshipEngineMenu> {
    public AirshipEngineScreen(AirshipEngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, AirshipEngineLayout.PANEL_W, AirshipEngineLayout.PANEL_H);
    }

    @Override
    protected void init() {
        super.init();
        // The default labels are replaced by our own drawing.
        titleLabelY = -1000;
        inventoryLabelY = -1000;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        extractTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        AirshipGuiStyle.panel(g, x, y, imageWidth, imageHeight);

        // Title
        AirshipGuiStyle.flame(g, x + 10, y + 8);
        g.text(font, Component.literal("Luftschiff-Antrieb").withStyle(ChatFormatting.BOLD),
                x + 23, y + 9, AirshipGuiStyle.TEXT, false);
        g.text(font, "Brennt nur bei Schub", x + 10, y + 22, AirshipGuiStyle.MUTED, false);

        // Normal tank: slot, arrow and the big timer (it only counts down while the ship thrusts)
        AirshipGuiStyle.slot(g, x + AirshipEngineLayout.FUEL_X, y + AirshipEngineLayout.FUEL_Y);
        AirshipGuiStyle.arrow(g, x + 32, y + 43, AirshipGuiStyle.MUTED);

        int fuel = menu.getFuel();
        int left = x + 48;
        int right = x + imageWidth - 10;
        g.text(font, "Restlaufzeit", left, y + 32, AirshipGuiStyle.MUTED, false);
        String max = "von " + AirshipFuel.time(AirshipEngineBlockEntity.MAX_FUEL) + " Min";
        g.text(font, max, right - font.width(max), y + 32, AirshipGuiStyle.MUTED, false);

        String timer = AirshipFuel.time(fuel);
        g.pose().pushMatrix();
        g.pose().translate((float) left, (float) (y + 42));
        g.pose().scale(2.0F);
        g.text(font, Component.literal(timer).withStyle(ChatFormatting.BOLD), 0, 0,
                fuel > 0 ? AirshipGuiStyle.TEXT : AirshipGuiStyle.MUTED, false);
        g.pose().popMatrix();
        g.text(font, "Min", left + font.width(timer) * 2 + 4, y + 51, AirshipGuiStyle.MUTED, false);

        AirshipGuiStyle.bar(g, left, y + 62, right - left, 7,
                (float) fuel / AirshipEngineBlockEntity.MAX_FUEL, AirshipGuiStyle.FLAME);

        // Turbo tank: burns 5x as fast, makes the ship much faster while the boost key is held
        AirshipGuiStyle.slot(g, x + AirshipEngineLayout.TURBO_X, y + AirshipEngineLayout.TURBO_Y);
        AirshipGuiStyle.arrow(g, x + 32, y + 85, AirshipGuiStyle.MUTED);

        int turbo = menu.getTurbo();
        g.text(font, Component.literal("Turbo").withStyle(ChatFormatting.BOLD), left, y + 76,
                AirshipGuiStyle.TURBO, false);
        String burn = AirshipEntity.TURBO_BURN_RATE + "x Verbrauch";
        g.text(font, burn, right - font.width(burn), y + 76, AirshipGuiStyle.MUTED, false);
        // The turbo tank is shown as the time of turbo it gives (its fuel burns TURBO_BURN_RATE times as fast).
        String turboTime = AirshipFuel.time(turbo / AirshipEntity.TURBO_BURN_RATE);
        g.text(font, Component.literal(turboTime + " Min Turbo").withStyle(ChatFormatting.BOLD), left, y + 86,
                turbo > 0 ? AirshipGuiStyle.TEXT : AirshipGuiStyle.MUTED, false);
        AirshipGuiStyle.bar(g, left, y + 97, right - left, 7,
                (float) turbo / AirshipEngineBlockEntity.MAX_TURBO, AirshipGuiStyle.TURBO);

        // Stat tiles
        tile(g, x + 10, y + 110, 88, "Treibstoff", speedText(AirshipEntity.FUEL_SPEED));
        tile(g, x + 106, y + 110, 88, "Turbo", speedText(AirshipEntity.TURBO_SPEED));

        // Inventory
        g.text(font, "Inventar", x + 10, y + 142, AirshipGuiStyle.TEXT, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                AirshipGuiStyle.slot(g, x + AirshipEngineLayout.INV_X + column * 18,
                        y + AirshipEngineLayout.INV_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            AirshipGuiStyle.slot(g, x + AirshipEngineLayout.INV_X + column * 18,
                    y + AirshipEngineLayout.INV_Y + 58);
        }
    }

    /** Speed per tick as "5 b/s" (blocks per second). */
    private static String speedText(double perTick) {
        double perSecond = perTick * 20.0;
        String number = perSecond == Math.floor(perSecond) ? String.valueOf((int) perSecond)
                : String.format("%.1f", perSecond);
        return number + " b/s";
    }

    private void tile(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
        g.fill(x, y, x + w, y + 28, AirshipGuiStyle.SLOT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + 27, AirshipGuiStyle.PANEL);
        g.text(font, label, x + 5, y + 4, AirshipGuiStyle.MUTED, false);
        g.text(font, Component.literal(value).withStyle(ChatFormatting.BOLD), x + 5, y + 15, AirshipGuiStyle.TEXT, false);
    }
}
