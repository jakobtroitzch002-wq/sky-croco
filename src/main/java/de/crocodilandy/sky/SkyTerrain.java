package de.crocodilandy.sky;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class SkyTerrain {
    private SkyTerrain() {}

    static int surfaceHeight(SkyIsland island, int x, int z) {
        double distance = SkyIslandGenerator.horizontalDistance(island, x, z);
        double edgeRadius = SkyIslandGenerator.edgeRadius(island, x, z);
        if (distance > edgeRadius) return Integer.MIN_VALUE;

        double terrain = SkyIslandGenerator.noise(x, z, island.noiseOffset()) * 3.5
                + SkyIslandGenerator.noise(x * 2.3, z * 2.3, island.noiseOffset() + 734.0) * 1.8;

        if (island.biomeType() == SkyChunkGenerator.BIOME_MOUNTAINS && island.mainIsland()) {
            terrain += mountainTerrain(island, x, z);
        }
        if (island.biomeType() == SkyChunkGenerator.BIOME_TAIGA
                || island.biomeType() == SkyChunkGenerator.BIOME_FOREST
                || island.biomeType() == SkyChunkGenerator.BIOME_JUNGLE
                || island.biomeType() == SkyChunkGenerator.BIOME_BIRCH
                || island.biomeType() == SkyChunkGenerator.BIOME_CHERRY) {
            terrain += SkyIslandGenerator.noise(x * 0.5, z * 0.5, island.noiseOffset() + 3300.0) * 5.0;
        }
        if (island.biomeType() == SkyChunkGenerator.BIOME_ROCKY
                || island.biomeType() == SkyChunkGenerator.BIOME_SAVANNA) {
            terrain += SkyIslandGenerator.noise(x * 0.42, z * 0.42, island.noiseOffset() + 4500.0) * 6.0;
        }
        if (island.biomeType() == SkyChunkGenerator.BIOME_DESERT
                || island.biomeType() == SkyChunkGenerator.BIOME_MESA) terrain *= 0.45;
        if (island.biomeType() == SkyChunkGenerator.BIOME_SWAMP) terrain *= 0.35;

        return island.surfaceY() + (int) Math.round(terrain);
    }

    static int bottomHeight(SkyIsland island, int x, int z) {
        double distance = SkyIslandGenerator.horizontalDistance(island, x, z);
        double edge = Math.min(1.0, distance / SkyIslandGenerator.edgeRadius(island, x, z));
        double noise = SkyIslandGenerator.noise(x * 0.8, z * 0.8, island.noiseOffset() + 8912.0);
        return island.bottomY() + (int) Math.round(
                noise * 2.5 + Math.pow(edge, 2.0) * island.radius() * 0.16);
    }

    static boolean isInside(SkyIsland island, int x, int y, int z, int surface, int bottom) {
        if (y > surface || y < bottom) return false;
        double distance = SkyIslandGenerator.horizontalDistance(island, x, z);
        double edgeRadius = SkyIslandGenerator.edgeRadius(island, x, z);
        double vertical = (double) (y - bottom) / Math.max(1, surface - bottom);
        double shape = 0.045 + Math.pow(vertical, 0.48) * 0.955;
        double rockNoise = SkyIslandGenerator.noise(x * 0.9, z * 0.9,
                island.noiseOffset() + y * 0.17);
        double allowed = edgeRadius * shape
                + rockNoise * island.roughness() * 3.5 * (1.0 - vertical);
        return distance <= allowed;
    }

    static BlockState blockState(SkyIsland island, int y, int surface) {
        int depth = surface - y;
        return switch (island.biomeType()) {
            case SkyChunkGenerator.BIOME_MESA -> mesa(depth, y, island.noiseOffset());
            case SkyChunkGenerator.BIOME_DESERT ->
                    depth <= 4 ? Blocks.SAND.defaultBlockState()
                            : depth <= 7 ? Blocks.SANDSTONE.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
            case SkyChunkGenerator.BIOME_MOUNTAINS, SkyChunkGenerator.BIOME_SNOW ->
                    depth == 0 ? (island.biomeType() == SkyChunkGenerator.BIOME_SNOW
                            ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState())
                            : depth <= 3 ? Blocks.DIRT.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
            case SkyChunkGenerator.BIOME_MUSHROOM ->
                    depth == 0 ? Blocks.MYCELIUM.defaultBlockState()
                            : depth <= 4 ? Blocks.DIRT.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
            case SkyChunkGenerator.BIOME_ROCKY ->
                    depth <= 2 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
            case SkyChunkGenerator.BIOME_SAVANNA ->
                    depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : depth <= 3 ? Blocks.COARSE_DIRT.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
            default ->
                    depth == 0 ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : depth <= 4 ? Blocks.DIRT.defaultBlockState()
                            : Blocks.STONE.defaultBlockState();
        };
    }

    private static BlockState mesa(int depth, int y, double offset) {
        if (depth == 0) return Blocks.RED_SAND.defaultBlockState();
        double layerOffset = depth <= 2 ? 0.0 : depth <= 5 ? 17.0 : 31.0;
        int layer = Math.floorMod((int) Math.floor(y + (offset + layerOffset) * 0.013), 64);
        return switch (layer) {
            case 0, 1, 2, 32, 33, 34 -> Blocks.TERRACOTTA.defaultBlockState();
            case 3, 4, 35, 36 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 5, 6 -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case 7, 8 -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case 9, 10 -> Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
            case 11, 12 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case 13, 14, 37, 38, 47, 48 -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case 15 -> Blocks.MAGENTA_TERRACOTTA.defaultBlockState();
            case 16, 17 -> Blocks.PINK_TERRACOTTA.defaultBlockState();
            case 18, 19 -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case 20, 21 -> Blocks.CYAN_TERRACOTTA.defaultBlockState();
            case 22, 23 -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case 24, 25 -> Blocks.LIME_TERRACOTTA.defaultBlockState();
            case 26, 27 -> Blocks.BLUE_TERRACOTTA.defaultBlockState();
            case 28, 29 -> Blocks.PURPLE_TERRACOTTA.defaultBlockState();
            case 30, 31 -> Blocks.BLACK_TERRACOTTA.defaultBlockState();
            case 39, 40, 49, 50 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 41, 42, 51, 52 -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case 43, 44 -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case 45, 46 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case 53, 54 -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case 55, 56 -> Blocks.PURPLE_TERRACOTTA.defaultBlockState();
            case 57, 58 -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case 59, 60 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            default -> Blocks.TERRACOTTA.defaultBlockState();
        };
    }

    private static double mountainTerrain(int x, int z, SkyIsland island) {
        double dx = x - island.x();
        double dz = z - island.z();
        double radius = Math.max(1.0, island.radius());
        double radial = Math.sqrt(dx * dx + dz * dz) / radius;
        if (radial >= 0.78) return 0.0;

        double rim = 1.0 - smoothstep(0.58, 0.78, radial);
        double seed = island.noiseOffset();
        double peak1 = gaussianPeak(x, z, island, seed + 11.0, 0.20, 0.35, 0.58);
        double peak2 = gaussianPeak(x, z, island, seed + 37.0, -0.25, -0.05, 0.78);
        double peak3 = gaussianPeak(x, z, island, seed + 83.0, 0.10, -0.38, 0.42);
        double ridges = Math.max(0.0,
                SkyIslandGenerator.noise(x * 0.075, z * 0.075, seed + 1200.0) * 0.75
                        + Math.abs(SkyIslandGenerator.noise(x * 0.13, z * 0.13, seed + 2400.0)) * 0.55);
        double peaks = Math.max(peak1, Math.max(peak2, peak3));
        double shape = Math.pow(Math.max(0.0, 1.0 - radial / 0.80), 0.35);
        return Math.max(0.0, peaks * 48.0 + ridges * peaks * 18.0) * rim * shape;
    }

    private static double gaussianPeak(int x, int z, SkyIsland island,
                                        double seed, double px, double pz, double heightFactor) {
        double angle = (seed * 0.00013) % (Math.PI * 2.0);
        double ca = Math.cos(angle), sa = Math.sin(angle);
        double rx = px * island.radius();
        double rz = pz * island.radius();
        double cx = island.x() + rx * ca - rz * sa;
        double cz = island.z() + rx * sa + rz * ca;
        double dx = x - cx;
        double dz = z - cz;
        double sigma = island.radius() * 0.22;
        return Math.exp(-(dx * dx + dz * dz) / Math.max(1.0, sigma * sigma)) * heightFactor;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }
}
