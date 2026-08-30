package de.crocodilandy.sky;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Small deterministic structures placed after the island terrain exists.
 * This deliberately avoids vanilla structure placement, which uses the
 * generator's world minimum height and was previously producing floating/
 * underground structures.
 */
public final class SkyStructurePlacer {
    private SkyStructurePlacer() {}

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(SkyStructurePlacer::onChunkLoad);
    }

    private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newChunk) {
        if (!newChunk) return;
        if (!level.dimension().equals(ServerLevel.OVERWORLD)) return;

        ChunkPos cp = chunk.getPos();
        long seed = mix(level.getSeed(), cp.x, cp.z);

        // Sparse: most chunks contain no structure.
        if (Math.floorMod(seed, 100) >= 7) return;

        int centerX = cp.getMinBlockX() + 8;
        int centerZ = cp.getMinBlockZ() + 8;
        int biome = SkyChunkGenerator.getBiomeTypeForPosition(level.getSeed(), centerX, centerZ);
        if (biome < 0) return;

        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);
        if (y <= 0 || y >= level.getMaxY() - 12) return;

        BlockState below = level.getBlockState(new BlockPos(centerX, y - 1, centerZ));
        if (below.isAir() || below.is(Blocks.WATER) || below.is(Blocks.LAVA)) return;

        BlockPos origin = new BlockPos(centerX - 4, y, centerZ - 4);
        switch (biome) {
            case SkyChunkGenerator.BIOME_DESERT -> buildDesertRuins(level, origin);
            case SkyChunkGenerator.BIOME_MESA -> buildBadlandsRuins(level, origin);
            case SkyChunkGenerator.BIOME_MOUNTAINS -> buildMountainWatchtower(level, origin);
            case SkyChunkGenerator.BIOME_SWAMP -> buildSwampHut(level, origin);
            case SkyChunkGenerator.BIOME_JUNGLE -> buildJungleTemple(level, origin);
            default -> buildSmallCamp(level, origin);
        }
    }

    private static void buildSmallCamp(ServerLevel l, BlockPos o) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) {
            if (x == 0 || x == 8 || z == 0 || z == 8) l.setBlock(o.offset(x, 0, z), Blocks.COBBLESTONE.defaultBlockState(), 3);
        }
        for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++) l.setBlock(o.offset(x, 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        l.setBlock(o.offset(4, 1, 4), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        l.setBlock(o.offset(4, 2, 4), Blocks.CAMPFIRE.defaultBlockState(), 3);
    }

    private static void buildDesertRuins(ServerLevel l, BlockPos o) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) {
            if (x == 0 || x == 8 || z == 0 || z == 8) l.setBlock(o.offset(x, 0, z), Blocks.SANDSTONE.defaultBlockState(), 3);
        }
        for (int h = 1; h < 5; h++) {
            l.setBlock(o.offset(0, h, 0), Blocks.SANDSTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(8, h, 0), Blocks.SANDSTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(0, h, 8), Blocks.SANDSTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(8, h, 8), Blocks.SANDSTONE.defaultBlockState(), 3);
        }
        l.setBlock(o.offset(4, 0, 4), Blocks.CHEST.defaultBlockState(), 3);
    }

    private static void buildBadlandsRuins(ServerLevel l, BlockPos o) {
        BlockState terracotta = Blocks.ORANGE_TERRACOTTA.defaultBlockState();
        for (int h = 0; h < 6; h++) {
            for (int x = 1; x < 8; x++) {
                l.setBlock(o.offset(x, h, 1), terracotta, 3);
                l.setBlock(o.offset(x, h, 7), terracotta, 3);
            }
        }
        l.setBlock(o.offset(4, 0, 4), Blocks.CHEST.defaultBlockState(), 3);
    }

    private static void buildMountainWatchtower(ServerLevel l, BlockPos o) {
        for (int h = 0; h < 8; h++) {
            for (int x = 2; x <= 6; x++) for (int z = 2; z <= 6; z++) {
                if (x == 2 || x == 6 || z == 2 || z == 6) l.setBlock(o.offset(x, h, z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) l.setBlock(o.offset(x, 8, z), Blocks.STONE_BRICK_SLAB.defaultBlockState(), 3);
    }

    private static void buildSwampHut(ServerLevel l, BlockPos o) {
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) l.setBlock(o.offset(x, 2, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        for (int h = 0; h < 3; h++) {
            l.setBlock(o.offset(1, h, 1), Blocks.OAK_FENCE.defaultBlockState(), 3);
            l.setBlock(o.offset(7, h, 1), Blocks.OAK_FENCE.defaultBlockState(), 3);
            l.setBlock(o.offset(1, h, 7), Blocks.OAK_FENCE.defaultBlockState(), 3);
            l.setBlock(o.offset(7, h, 7), Blocks.OAK_FENCE.defaultBlockState(), 3);
        }
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) l.setBlock(o.offset(x, 5, z), Blocks.SPRUCE_PLANKS.defaultBlockState(), 3);
    }

    private static void buildJungleTemple(ServerLevel l, BlockPos o) {
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) {
            if (x == 1 || x == 7 || z == 1 || z == 7) l.setBlock(o.offset(x, 0, z), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
        }
        for (int h = 1; h < 5; h++) {
            l.setBlock(o.offset(1, h, 1), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(7, h, 1), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(1, h, 7), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
            l.setBlock(o.offset(7, h, 7), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 3);
        }
        l.setBlock(o.offset(4, 1, 4), Blocks.CHEST.defaultBlockState(), 3);
    }

    private static long mix(long s, long x, long z) {
        long v = s ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        v ^= v >>> 30; v *= 0xBF58476D1CE4E5B9L;
        v ^= v >>> 27; v *= 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }
}
