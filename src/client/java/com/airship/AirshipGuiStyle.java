package com.airship;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the airship screens: parchment, walnut frame and brass (design "Messing &amp; Dampf"). */
final class AirshipGuiStyle {
    static final int PANEL = 0xFFD8C294;
    static final int FRAME = 0xFF3A2415;
    static final int BRASS = 0xFFC8964B;
    static final int BRASS_DARK = 0xFF8B6128;
    static final int TEXT = 0xFF2B1B0F;
    static final int MUTED = 0xFF5E4630;
    static final int SLOT = 0xFFB79F6E;
    static final int SLOT_DARK = 0xFF8A7248;
    static final int SLOT_LIGHT = 0xFFEBDDB6;
    static final int FLAME = 0xFFC2561E;
    static final int EMBER = 0xFFFFC27A;
    static final int OK = 0xFF2E7D5B;
    static final int BAD = 0xFFB5472F;
    static final int BAR = 0xFF2F7F72;
    static final int TURBO = 0xFFB5472F;
    static final int WHITE = 0xFFFFFFFF;

    private AirshipGuiStyle() {}

    /** Walnut frame, a thin brass line inside it, parchment, and a brass rivet in each corner. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, FRAME);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, BRASS);
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, PANEL);
        for (int[] corner : new int[][] {{1, 1}, {w - 3, 1}, {1, h - 3}, {w - 3, h - 3}}) {
            g.fill(x + corner[0], y + corner[1], x + corner[0] + 2, y + corner[1] + 2, BRASS);
        }
    }

    /** An 18x18 inset slot background. */
    static void slot(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, SLOT_LIGHT);
        g.fill(x, y, x + 17, y + 17, SLOT_DARK);
        g.fill(x + 1, y + 1, x + 17, y + 17, SLOT);
    }

    /** A bar with a dark outline; {@code fraction} is 0..1 (a non-zero value always shows at least one pixel). */
    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float fraction, int color) {
        g.fill(x, y, x + w, y + h, SLOT_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, SLOT);
        int inner = w - 2;
        int filled = Math.round(inner * Math.max(0.0F, Math.min(1.0F, fraction)));
        if (fraction > 0.0F && filled < 1) {
            filled = 1;
        }
        if (filled > 0) {
            g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, color);
        }
    }

    static void check(GuiGraphicsExtractor g, int x, int y, int color) {
        int[][] points = {{0, 4}, {1, 5}, {2, 6}, {3, 5}, {4, 4}, {5, 3}, {6, 2}, {7, 1}};
        for (int[] p : points) {
            g.fill(x + p[0], y + p[1], x + p[0] + 1, y + p[1] + 2, color);
        }
    }

    static void cross(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 8; i++) {
            g.fill(x + i, y + i, x + i + 1, y + i + 2, color);
            g.fill(x + 7 - i, y + i, x + 8 - i, y + i + 2, color);
        }
    }

    static void flame(GuiGraphicsExtractor g, int x, int y) {
        int[][] rows = {{0, 4, 5}, {1, 3, 6}, {2, 3, 6}, {3, 2, 7}, {4, 2, 7}, {5, 1, 8}, {6, 1, 8}, {7, 1, 8}, {8, 2, 7}, {9, 3, 6}};
        for (int[] r : rows) {
            g.fill(x + r[1], y + r[0], x + r[2], y + r[0] + 1, FLAME);
        }
        g.fill(x + 3, y + 6, x + 6, y + 8, EMBER);
        g.fill(x + 4, y + 8, x + 5, y + 9, EMBER);
    }

    /** A small compass: ring, cross and needle. */
    static void compass(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x + 2, y, x + 9, y + 1, BRASS_DARK);
        g.fill(x + 2, y + 10, x + 9, y + 11, BRASS_DARK);
        g.fill(x, y + 2, x + 1, y + 9, BRASS_DARK);
        g.fill(x + 10, y + 2, x + 11, y + 9, BRASS_DARK);
        g.fill(x + 1, y + 1, x + 2, y + 2, BRASS_DARK);
        g.fill(x + 9, y + 1, x + 10, y + 2, BRASS_DARK);
        g.fill(x + 1, y + 9, x + 2, y + 10, BRASS_DARK);
        g.fill(x + 9, y + 9, x + 10, y + 10, BRASS_DARK);
        g.fill(x + 5, y + 2, x + 6, y + 9, BRASS_DARK);
        g.fill(x + 2, y + 5, x + 9, y + 6, BRASS_DARK);
        g.fill(x + 4, y + 4, x + 7, y + 7, FLAME);
    }

    static void arrow(GuiGraphicsExtractor g, int x, int y, int color) {
        g.fill(x, y + 3, x + 9, y + 5, color);
        g.fill(x + 7, y + 1, x + 8, y + 7, color);
        g.fill(x + 8, y + 2, x + 9, y + 6, color);
        g.fill(x + 9, y + 3, x + 10, y + 5, color);
    }

    /** Fuel time as m:ss. */
    static String time(int ticks) {
        int seconds = ticks / 20;
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }
}
