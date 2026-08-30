package de.crocodilandy.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;
import java.util.ArrayList;

/**
 * Deterministic resource pass used directly by SkyChunkGenerator.
 * It does not listen to chunk-load events and therefore cannot modify
 * already loaded chunks after generation.
 */
public final class SkyResourceGenerator {

    private SkyResourceGenerator() {
    }

    public static void addResources(ChunkAccess chunk, List<BlockPos> solidPositions, long seed) {
        if (solidPositions.isEmpty()) return;

        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : solidPositions) {
            BlockState state = chunk.getBlockState(pos);
            if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) {
                candidates.add(pos.immutable());
            }
        }

        if (candidates.isEmpty()) return;

        int attempts = Math.min(14, Math.max(3, candidates.size() / 80));
        for (int attempt = 0; attempt < attempts; attempt++) {
            long veinSeed = mixSeed(seed, attempt, 0x51A7L);
            BlockPos start = candidates.get((int) Math.floorMod(veinSeed, candidates.size()));
            BlockState ore = chooseOre(veinSeed);
            int size = veinSize(ore, veinSeed);
            placeVein(chunk, start, ore, size, veinSeed);
        }
    }

    private static int veinSize(BlockState ore, long seed) {
        if (ore.is(Blocks.DIAMOND_ORE)) return 3;
        if (ore.is(Blocks.EMERALD_ORE)) return 2;
        if (ore.is(Blocks.LAPIS_ORE)) return 4;
        if (ore.is(Blocks.REDSTONE_ORE)) return 5;
        if (ore.is(Blocks.GOLD_ORE)) return 5;
        if (ore.is(Blocks.IRON_ORE)) return 6;
        if (ore.is(Blocks.COPPER_ORE)) return 7;
        return 7 + (int) Math.floorMod(seed, 4);
    }

    private static void placeVein(ChunkAccess chunk, BlockPos start, BlockState ore, int size, long seed) {
        for (int i = 0; i < size; i++) {
            long value = mixSeed(seed, i, 0xBEEFL);
            int dx = (int) Math.floorMod(value, 5) - 2;
            int dy = (int) Math.floorMod(value >>> 8, 3) - 1;
            int dz = (int) Math.floorMod(value >>> 16, 5) - 2;

            BlockPos pos = start.offset(dx, dy, dz);
            BlockState current = chunk.getBlockState(pos);
            if (current.is(Blocks.STONE) || current.is(Blocks.DEEPSLATE)) {
                chunk.setBlockState(pos, ore, 0);
            }
        }
    }

    private static BlockState chooseOre(long seed) {
        int roll = (int) Math.floorMod(seed, 100);
        if (roll < 2) return Blocks.EMERALD_ORE.defaultBlockState();
        if (roll < 6) return Blocks.DIAMOND_ORE.defaultBlockState();
        if (roll < 12) return Blocks.LAPIS_ORE.defaultBlockState();
        if (roll < 21) return Blocks.REDSTONE_ORE.defaultBlockState();
        if (roll < 31) return Blocks.GOLD_ORE.defaultBlockState();
        if (roll < 48) return Blocks.IRON_ORE.defaultBlockState();
        if (roll < 70) return Blocks.COPPER_ORE.defaultBlockState();
        return Blocks.COAL_ORE.defaultBlockState();
    }

    private static long mixSeed(long seed, long x, long z) {
        long value = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }
}
