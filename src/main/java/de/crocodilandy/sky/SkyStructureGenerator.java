package de.crocodilandy.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Entry point for island-aware structure placement.
 *
 * Vanilla still owns the actual StructureStart creation and piece placement.
 * We only reject candidate starts whose actual generated footprint does not
 * fit inside a deterministic SkyIsland.
 */
public final class SkyStructureGenerator {
    private static final int DEFAULT_STRUCTURE_FOOTPRINT_RADIUS = 12;
    private static final int STRUCTURE_FOOTPRINT_SAMPLE_STEP = 4;

    private SkyStructureGenerator() {
    }

    public static BlockPos findSafeAnchor(ServerLevel level, BlockPos preferred, int footprintRadius) {
        return SkyChunkGenerator.findStructurePlacement(
                level.getSeed(), preferred.getX(), preferred.getZ(), footprintRadius);
    }

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

    /**
     * Validates the actual X/Z footprint returned by Structure.generate.
     *
     * A structure start is accepted only when every sampled point on its
     * bounding-box perimeter belongs to the same deterministic island.
     */
    public static boolean isStructureBoundingBoxOnIsland(long seed, BoundingBox box) {
        int minX = box.minX();
        int maxX = box.maxX();
        int minZ = box.minZ();
        int maxZ = box.maxZ();

        if (maxX < minX || maxZ < minZ) {
            return false;
        }

        int centerX = minX + (maxX - minX) / 2;
        int centerZ = minZ + (maxZ - minZ) / 2;
        SkyIsland island = SkyChunkGenerator.findStructureIsland(
                seed,
                centerX,
                centerZ,
                Math.max(maxX - minX, maxZ - minZ) / 2
        );
        if (island == null) {
            return false;
        }

        for (int x = minX; x <= maxX; x += STRUCTURE_FOOTPRINT_SAMPLE_STEP) {
            if (!isOnIsland(island, x, minZ) || !isOnIsland(island, x, maxZ)) {
                return false;
            }
        }

        for (int z = minZ; z <= maxZ; z += STRUCTURE_FOOTPRINT_SAMPLE_STEP) {
            if (!isOnIsland(island, minX, z) || !isOnIsland(island, maxX, z)) {
                return false;
            }
        }

        return isOnIsland(island, maxX, minZ)
                && isOnIsland(island, minX, maxZ);
    }

    private static boolean isOnIsland(SkyIsland island, int x, int z) {
        return SkyIslandGenerator.findAt(java.util.List.of(island), x, z) != null;
    }
}
