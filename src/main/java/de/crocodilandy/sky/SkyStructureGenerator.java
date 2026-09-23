package de.crocodilandy.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Entry point for island-aware structure placement. The generator deliberately
 * keeps the placement decision separate from chunk loading so structure starts
 * can be validated against deterministic SkyIsland geometry before blocks exist.
 */
public final class SkyStructureGenerator {
    private SkyStructureGenerator() {
    }

    /**
     * Finds a deterministic surface anchor near the requested location.
     * Returns null when no island with enough footprint is nearby.
     */
    public static BlockPos findSafeAnchor(ServerLevel level, BlockPos preferred, int footprintRadius) {
        return SkyChunkGenerator.findStructurePlacement(
                level.getSeed(), preferred.getX(), preferred.getZ(), footprintRadius);
    }
}
