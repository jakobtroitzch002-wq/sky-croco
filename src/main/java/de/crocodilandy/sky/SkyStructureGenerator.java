package de.crocodilandy.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Entry point for island-aware structure placement.
 *
 * Vanilla still owns the actual StructureStart creation and piece placement.
 * This class only decides whether the deterministic candidate chunk has a
 * valid island footprint large enough to host the structure.
 */
public final class SkyStructureGenerator {
    private static final int DEFAULT_STRUCTURE_FOOTPRINT_RADIUS = 12;

    private SkyStructureGenerator() {
    }

    public static BlockPos findSafeAnchor(ServerLevel level, BlockPos preferred, int footprintRadius) {
        return SkyChunkGenerator.findStructurePlacement(
                level.getSeed(), preferred.getX(), preferred.getZ(), footprintRadius);
    }

    /**
     * Returns whether a vanilla structure candidate chunk has enough island
     * terrain around its center to host a normal-sized structure footprint.
     */
    public static boolean isValidStructureChunk(long seed, int chunkX, int chunkZ) {
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        return SkyChunkGenerator.findStructureIsland(
                seed,
                centerX,
                centerZ,
                DEFAULT_STRUCTURE_FOOTPRINT_RADIUS
        ) != null;
    }
}
