package de.crocodilandy.sky;

import net.minecraft.util.RandomSource;
import java.util.ArrayList;
import java.util.List;

final class SkyIslandGenerator {
    static final int GROUP_CELL_SIZE = 850;
    static final int MIN_SMALL_ISLANDS = 3;
    static final int MAX_SMALL_ISLANDS = 7;
    static final double MAIN_ISLAND_MIN_RADIUS = 26.0;
    static final double MAIN_ISLAND_MAX_RADIUS = 39.0;
    static final double SMALL_ISLAND_MIN_RADIUS = 5.0;
    static final double SMALL_ISLAND_MAX_RADIUS = 14.0;
    static final double GROUP_RADIUS = 118.0;
    static final int BIOME_COUNT = 14;

    private SkyIslandGenerator() {}

    static List<SkyIsland> findNearby(long worldSeed, int x, int z) {
        List<SkyIsland> result = new ArrayList<>();
        int cellX = Math.floorDiv(x, GROUP_CELL_SIZE);
        int cellZ = Math.floorDiv(z, GROUP_CELL_SIZE);

        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int gx = cellX + ox;
                int gz = cellZ + oz;
                RandomSource random = RandomSource.create(mixSeed(worldSeed, gx, gz));
                boolean spawnCell = gx == 0 && gz == 0;
                if (!spawnCell && random.nextDouble() > 0.36) continue;

                double cx = gx * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                double cz = gz * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                int biome = random.nextInt(BIOME_COUNT);

                double radius = spawnCell
                        ? 45.0 + random.nextDouble() * 12.0
                        : random.nextDouble() < 0.10
                                ? 35.0 + random.nextDouble() * 8.0
                                : MAIN_ISLAND_MIN_RADIUS
                                    + random.nextDouble() * (MAIN_ISLAND_MAX_RADIUS - MAIN_ISLAND_MIN_RADIUS);

                int surface = 105 + random.nextInt(-7, 8);
                int bottom = 34 + random.nextInt(14);
                result.add(create(random, cx, cz, radius, surface, bottom, biome, true));

                int count = MIN_SMALL_ISLANDS + random.nextInt(MAX_SMALL_ISLANDS - MIN_SMALL_ISLANDS + 1);
                for (int i = 0; i < count; i++) {
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    double distance = 42.0 + Math.pow(random.nextDouble(), 0.72) * GROUP_RADIUS;
                    double ix = cx + Math.cos(angle) * distance;
                    double iz = cz + Math.sin(angle) * distance;
                    double ir = SMALL_ISLAND_MIN_RADIUS
                            + random.nextDouble() * (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
                    int is = 105 + random.nextInt(-9, 10);
                    int ib = is - 24 - random.nextInt(18);
                    result.add(create(random, ix, iz, ir, is, ib, biome, false));
                }
            }
        }
        return result;
    }

    static SkyIsland findAt(List<SkyIsland> islands, int x, int z) {
        SkyIsland best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SkyIsland island : islands) {
            double distance = horizontalDistance(island, x, z);
            if (distance > edgeRadius(island, x, z)) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = island;
            }
        }
        return best;
    }

    static int biomeAt(long seed, int x, int z) {
        int cellX = Math.floorDiv(x, GROUP_CELL_SIZE);
        int cellZ = Math.floorDiv(z, GROUP_CELL_SIZE);
        int bestBiome = -1;
        double bestDistance = Double.MAX_VALUE;

        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int gx = cellX + ox, gz = cellZ + oz;
                RandomSource random = RandomSource.create(mixSeed(seed, gx, gz));
                boolean spawnCell = gx == 0 && gz == 0;
                if (!spawnCell && random.nextDouble() > 0.36) continue;

                double cx = gx * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                double cz = gz * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                int biome = random.nextInt(BIOME_COUNT);

                double mainRadius = spawnCell ? 57.0
                        : (random.nextDouble() < 0.10 ? 43.0 : 39.0);
                double d = Math.hypot(x - cx, z - cz);
                if (d <= mainRadius * 1.45 && d < bestDistance) {
                    bestDistance = d;
                    bestBiome = biome;
                }

                int count = MIN_SMALL_ISLANDS + random.nextInt(MAX_SMALL_ISLANDS - MIN_SMALL_ISLANDS + 1);
                for (int i = 0; i < count; i++) {
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    double distance = 42.0 + Math.pow(random.nextDouble(), 0.72) * GROUP_RADIUS;
                    double ix = cx + Math.cos(angle) * distance;
                    double iz = cz + Math.sin(angle) * distance;
                    double ir = SMALL_ISLAND_MIN_RADIUS
                            + random.nextDouble() * (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
                    d = Math.hypot(x - ix, z - iz);
                    if (d <= ir * 1.45 && d < bestDistance) {
                        bestDistance = d;
                        bestBiome = biome;
                    }
                }
            }
        }
        return bestBiome;
    }

    private static SkyIsland create(RandomSource random, double x, double z, double radius,
                                    int surfaceY, int bottomY, int biome, boolean main) {
        return new SkyIsland(
                x, z, radius, surfaceY, bottomY,
                random.nextDouble() * Math.PI * 2.0,
                0.80 + random.nextDouble() * 0.45,
                0.80 + random.nextDouble() * 0.45,
                random.nextDouble() * 100000.0,
                biome, main,
                0.5 + random.nextDouble() * 0.7
        );
    }

    static double horizontalDistance(SkyIsland island, int x, int z) {
        double dx = x - island.x();
        double dz = z - island.z();
        double cos = Math.cos(island.rotation());
        double sin = Math.sin(island.rotation());
        double localX = (dx * cos + dz * sin) / island.stretchX();
        double localZ = (-dx * sin + dz * cos) / island.stretchZ();
        return Math.sqrt(localX * localX + localZ * localZ);
    }

    static double edgeRadius(SkyIsland island, int x, int z) {
        double angle = Math.atan2(z - island.z(), x - island.x());
        double directional = Math.sin(angle * 3.0 + island.noiseOffset()) * 0.12
                + Math.sin(angle * 5.0 + island.noiseOffset() * 0.31) * 0.08
                + Math.cos(angle * 7.0 - island.noiseOffset() * 0.19) * 0.055;
        double worldNoise = noise(x * 0.45, z * 0.45, island.noiseOffset() + 1731.0) * 0.08;
        return island.radius() * Math.max(0.62, 1.0 + directional + worldNoise);
    }

    static double noise(double x, double z, double offset) {
        return Math.sin(x * 0.035 + offset) * 0.30
                + Math.sin(z * 0.041 + offset * 1.37) * 0.25
                + Math.sin((x + z) * 0.019 + offset * 0.71) * 0.25
                + Math.cos((x - z) * 0.014 - offset * 1.13) * 0.20;
    }

    static long mixSeed(long seed, long x, long z) {
        long value = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
