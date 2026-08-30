package de.crocodilandy.sky;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Adds survival resources to generated Sky islands after the terrain has been created.
 * The generator is intentionally deterministic per world/chunk so resources do not
 * move between server restarts.
 */
public final class SkyResourceGenerator {

    private SkyResourceGenerator() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(SkyResourceGenerator::generateResources);
    }

    private static void generateResources(ServerLevel level, LevelChunk chunk) {
        RandomSource random = RandomSource.create(
                mixSeed(level.getSeed(), chunk.getPos().x, chunk.getPos().z)
        );

        int minY = Math.max(level.getMinY(), 35);
        int maxY = 108;

        generateVeins(chunk, random, Blocks.COAL_ORE, 8, 5, minY, maxY);
        generateVeins(chunk, random, Blocks.IRON_ORE, 7, 5, minY, maxY);
        generateVeins(chunk, random, Blocks.COPPER_ORE, 7, 6, minY, maxY);
        generateVeins(chunk, random, Blocks.GOLD_ORE, 4, 5, minY, maxY);
        generateVeins(chunk, random, Blocks.REDSTONE_ORE, 4, 5, minY, 82);
        generateVeins(chunk, random, Blocks.LAPIS_ORE, 3, 5, minY, 82);
        generateVeins(chunk, random, Blocks.DIAMOND_ORE, 2, 4, minY, 72);
        generateVeins(chunk, random, Blocks.EMERALD_ORE, 1, 3, 60, 105);
    }

    private static void generateVeins(
            LevelChunk chunk,
            RandomSource random,
            net.minecraft.world.level.block.Block ore,
            int attempts,
            int veinSize,
            int minY,
            int maxY
    ) {
        if (maxY < minY) {
            return;
        }

        for (int attempt = 0; attempt < attempts; attempt++) {
            int x = chunk.getPos().getMinBlockX() + random.nextInt(16);
            int z = chunk.getPos().getMinBlockZ() + random.nextInt(16);
            int y = minY + random.nextInt(maxY - minY + 1);

            placeVein(chunk, random, ore.defaultBlockState(), x, y, z, veinSize);
        }
    }

    private static void placeVein(
            LevelChunk chunk,
            RandomSource random,
            BlockState ore,
            int x,
            int y,
            int z,
            int size
    ) {
        for (int i = 0; i < size; i++) {
            int px = x + random.nextInt(3) - 1;
            int py = y + random.nextInt(3) - 1;
            int pz = z + random.nextInt(3) - 1;

            if (py < chunk.getMinBuildHeight() || py >= chunk.getMaxBuildHeight()) {
                continue;
            }

            BlockPos pos = new BlockPos(px, py, pz);
            BlockState current = chunk.getBlockState(pos);

            if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                chunk.setBlockState(pos, ore, 0);
            }
        }
    }

    private static long mixSeed(long seed, int x, int z) {
        long value = seed
                ^ ((long) x * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL);

        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }
}
