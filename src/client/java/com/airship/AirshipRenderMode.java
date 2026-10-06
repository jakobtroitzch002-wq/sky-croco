package com.airship;

/** Client-side switches for how the ship is drawn. */
final class AirshipRenderMode {
    /**
     * true: see-through blocks (glass ...) are drawn in a phase after the terrain, so they cannot cut away the water.
     * false: they are drawn together with the other ship blocks (water behind them may vanish).
     */
    static boolean afterTerrain = true;

    private AirshipRenderMode() {}
}
