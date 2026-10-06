package com.airship;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The Core screen: a checklist of what the ship has and what it is missing. */
public class AirshipCoreScreen extends Screen {
    private static final int PANEL_W = 224;
    private static final int PANEL_H = 210;

    private AirshipCoreInfoPayload info;

    public AirshipCoreScreen(AirshipCoreInfoPayload info) {
        super(Component.literal("Luftschiff-Kern"));
        this.info = info;
    }

    public boolean isFor(BlockPos pos) {
        return info.pos().equals(pos);
    }

    public void update(AirshipCoreInfoPayload newInfo) {
        this.info = newInfo;
    }

    @Override
    protected void init() {
        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("Neu prüfen"), button -> send(AirshipCoreActionPayload.RECHECK))
                .pos(x + 10, y + 160)
                .size(92, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Gelände vergessen"), button -> send(AirshipCoreActionPayload.FORGET_TERRAIN))
                .pos(x + PANEL_W - 10 - 92, y + 160)
                .size(92, 20)
                .build());
    }

    private void send(int action) {
        ClientPlayNetworking.send(new AirshipCoreActionPayload(info.pos(), action));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        AirshipGuiStyle.panel(g, x, y, PANEL_W, PANEL_H);

        // Title and status badge
        AirshipGuiStyle.compass(g, x + 10, y + 9);
        g.text(font, Component.literal("Luftschiff-Kern").withStyle(ChatFormatting.BOLD),
                x + 25, y + 11, AirshipGuiStyle.TEXT, false);
        String badge = info.ready() ? "BEREIT" : "NICHT BEREIT";
        int badgeWidth = font.width(badge) + 8;
        int badgeX = x + PANEL_W - 10 - badgeWidth;
        g.fill(badgeX, y + 9, badgeX + badgeWidth, y + 21, info.ready() ? AirshipGuiStyle.OK : AirshipGuiStyle.BAD);
        g.text(font, badge, badgeX + 4, y + 11, AirshipGuiStyle.WHITE, false);
        if (!info.ready()) {
            g.text(font, info.problem(), x + 10, y + 24, AirshipGuiStyle.BAD, false);
        }

        // Checklist
        int rowY = y + 36;
        rowY = row(g, x, rowY, "Steuersitz", info.seatBlocks() + " von 1", info.seatBlocks() == 1,
                Math.min(1.0F, info.seatBlocks()));
        float lift = info.balloonsRequired() == 0 ? 1.0F : (float) info.balloons() / info.balloonsRequired();
        rowY = row(g, x, rowY, "Auftrieb", info.balloons() + " Ballons · " + info.balloonsRequired() + " nötig",
                info.balloons() >= info.balloonsRequired(), lift);
        rowY = row(g, x, rowY, "Größe", info.blocks() + " von " + info.maxBlocks() + " Blöcken",
                info.blocks() < info.maxBlocks(), (float) info.blocks() / info.maxBlocks());
        rowY = row(g, x, rowY, "Sitzkissen", info.cushions() + " Mitfahrer-Plätze", true, -1.0F);
        rowY = row(g, x, rowY, "Tiere/Boote", info.animals() + " an Bord (fahren mit)", true, -1.0F);
        row(g, x, rowY, "Antriebe", info.engines() + " verbaut", true, -1.0F);

        g.text(font, "Zum Fliegen auf den Steuersitz setzen.", x + 10, y + 186, AirshipGuiStyle.MUTED, false);
        g.text(font, "Umgebaut? Erst hier prüfen.", x + 10, y + 196, AirshipGuiStyle.MUTED, false);
    }

    /** Draws one checklist row and returns the y of the next row. A negative fraction means: no bar. */
    private int row(GuiGraphicsExtractor g, int panelX, int y, String label, String value, boolean ok, float fraction) {
        int color = ok ? AirshipGuiStyle.OK : AirshipGuiStyle.BAD;
        if (ok) {
            AirshipGuiStyle.check(g, panelX + 10, y, color);
        } else {
            AirshipGuiStyle.cross(g, panelX + 10, y, color);
        }
        g.text(font, Component.literal(label).withStyle(ChatFormatting.BOLD), panelX + 23, y, AirshipGuiStyle.TEXT, false);
        g.text(font, value, panelX + PANEL_W - 10 - font.width(value), y, AirshipGuiStyle.MUTED, false);
        if (fraction < 0.0F) {
            return y + 15;
        }
        AirshipGuiStyle.bar(g, panelX + 23, y + 11, PANEL_W - 10 - 23, 6, fraction,
                ok ? AirshipGuiStyle.BAR : AirshipGuiStyle.BAD);
        return y + 24;
    }
}
