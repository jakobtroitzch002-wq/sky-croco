package de.crocodilandy.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Placeholder for the island-aware structure phase.
 * Vanilla structure generation is intentionally disabled in SkyChunkGenerator
 * until structures can be positioned against actual island terrain.
 */
public final class SkyStructureGenerator {
    private SkyStructureGenerator() {
    }

    public static void generate(ServerLevel level, BlockPos center) {
        // Intentionally empty in this intermediate build.
    }
}
