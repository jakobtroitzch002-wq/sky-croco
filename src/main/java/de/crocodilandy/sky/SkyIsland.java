package de.crocodilandy.sky;

/** Immutable description of one generated sky island. */
public record SkyIsland(
        double x,
        double z,
        double radius,
        int surfaceY,
        int bottomY,
        double rotation,
        double stretchX,
        double stretchZ,
        double noiseOffset,
        int biomeType,
        boolean mainIsland,
        double roughness
) {
}
