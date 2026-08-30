package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SkyChunkGenerator extends ChunkGenerator {
    public static final MapCodec<SkyChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(BiomeSource.CODEC.fieldOf("biome_source")
                    .forGetter(generator -> generator.biomeSource))
                    .apply(instance, SkyChunkGenerator::new));

    private static final int WORLD_MIN_Y = -64;
    private static final int SURFACE_Y = 105;
    private static final int GROUP_CELL_SIZE = 850;
    private static final int MIN_SMALL_ISLANDS = 3;
    private static final int MAX_SMALL_ISLANDS = 7;
    private static final double MAIN_ISLAND_MIN_RADIUS = 26.0;
    private static final double MAIN_ISLAND_MAX_RADIUS = 39.0;
    private static final double SMALL_ISLAND_MIN_RADIUS = 5.0;
    private static final double SMALL_ISLAND_MAX_RADIUS = 14.0;
    private static final double GROUP_RADIUS = 118.0;

    public static final int BIOME_MESA = 0;
    public static final int BIOME_TAIGA = 1;
    public static final int BIOME_MOUNTAINS = 2;
    public static final int BIOME_DESERT = 3;
    public static final int BIOME_FOREST = 4;
    public static final int BIOME_SNOW = 5;
    public static final int BIOME_MUSHROOM = 6;
    public static final int BIOME_ROCKY = 7;
    public static final int BIOME_PLAINS = 8;
    public static final int BIOME_JUNGLE = 9;
    public static final int BIOME_SWAMP = 10;
    public static final int BIOME_SAVANNA = 11;
    public static final int BIOME_CHERRY = 12;
    public static final int BIOME_BIRCH = 13;
    private static final int BIOME_COUNT = 14;

    private long worldSeed;
    private static volatile long CURRENT_WORLD_SEED;

    public SkyChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> structureSetLookup,
            RandomState randomState,
            long seed) {
        worldSeed = seed;
        CURRENT_WORLD_SEED = seed;
        return super.createState(structureSetLookup, randomState, seed);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {
        int startX = chunk.getPos().x() * 16;
        int startZ = chunk.getPos().z() * 16;
        List<Island> islands = findNearbyIslands(startX + 8, startZ + 8);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = startX + lx;
                int z = startZ + lz;
                Island island = findIslandAt(islands, x, z);
                if (island == null) continue;

                int surface = getSurfaceHeight(island, x, z);
                int bottom = getBottomHeight(island, x, z);
                if (surface == Integer.MIN_VALUE) continue;

                for (int y = bottom; y <= surface; y++) {
                    if (isInsideIsland(island, x, y, z, surface, bottom)) {
                        chunk.setBlockState(new BlockPos(x, y, z),
                                getBlockState(island, x, y, z, surface), 0);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private List<Island> findNearbyIslands(int x, int z) {
        List<Island> result = new ArrayList<>();
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

                double radius;
                if (spawnCell) radius = 45.0 + random.nextDouble() * 12.0;
                else if (random.nextDouble() < 0.10) radius = 35.0 + random.nextDouble() * 8.0;
                else radius = MAIN_ISLAND_MIN_RADIUS + random.nextDouble() *
                        (MAIN_ISLAND_MAX_RADIUS - MAIN_ISLAND_MIN_RADIUS);

                int surface = SURFACE_Y + random.nextInt(-7, 8);
                int bottom = 34 + random.nextInt(14);
                result.add(createIsland(random, cx, cz, radius, surface, bottom, biome, true));

                int count = MIN_SMALL_ISLANDS + random.nextInt(MAX_SMALL_ISLANDS - MIN_SMALL_ISLANDS + 1);
                for (int i = 0; i < count; i++) {
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    double distance = 42.0 + Math.pow(random.nextDouble(), 0.72) * GROUP_RADIUS;
                    double ix = cx + Math.cos(angle) * distance;
                    double iz = cz + Math.sin(angle) * distance;
                    double ir = SMALL_ISLAND_MIN_RADIUS + random.nextDouble() *
                            (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
                    int is = SURFACE_Y + random.nextInt(-9, 10);
                    int ib = is - 24 - random.nextInt(18);
                    result.add(createIsland(random, ix, iz, ir, is, ib, biome, false));
                }
            }
        }
        return result;
    }

    private Island createIsland(RandomSource random, double x, double z, double radius,
                                int surfaceY, int bottomY, int biome, boolean main) {
        return new Island(x, z, radius, surfaceY, bottomY,
                random.nextDouble() * Math.PI * 2.0,
                0.80 + random.nextDouble() * 0.45,
                0.80 + random.nextDouble() * 0.45,
                random.nextDouble() * 100000.0,
                biome, main, 0.5 + random.nextDouble() * 0.7);
    }

    private Island findIslandAt(List<Island> islands, int x, int z) {
        Island best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Island island : islands) {
            double distance = horizontalDistance(island, x, z);
            if (distance > getEdgeRadius(island, x, z)) continue;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = island;
            }
        }
        return best;
    }

    private int getSurfaceHeight(Island island, int x, int z) {
        double distance = horizontalDistance(island, x, z);
        double edgeRadius = getEdgeRadius(island, x, z);
        if (distance > edgeRadius) return Integer.MIN_VALUE;

        double terrain = terrainNoise(x, z, island.noiseOffset) * 3.5
                + terrainNoise(x * 2.3, z * 2.3, island.noiseOffset + 734.0) * 1.8;

        if (island.biomeType == BIOME_MOUNTAINS && island.mainIsland) {
            terrain += mountainTerrain(x, z, island);
        }
        if (island.biomeType == BIOME_TAIGA || island.biomeType == BIOME_FOREST
                || island.biomeType == BIOME_JUNGLE || island.biomeType == BIOME_BIRCH
                || island.biomeType == BIOME_CHERRY) {
            terrain += terrainNoise(x * 0.5, z * 0.5, island.noiseOffset + 3300.0) * 5.0;
        }
        if (island.biomeType == BIOME_ROCKY || island.biomeType == BIOME_SAVANNA) {
            terrain += terrainNoise(x * 0.42, z * 0.42, island.noiseOffset + 4500.0) * 6.0;
        }
        if (island.biomeType == BIOME_DESERT || island.biomeType == BIOME_MESA) terrain *= 0.45;
        if (island.biomeType == BIOME_SWAMP) terrain *= 0.35;

        return island.surfaceY + (int)Math.round(terrain);
    }

    /** Multiple mountain peaks on the main island, with a deliberately flat outer rim. */
    private double mountainTerrain(int x, int z, Island island) {
        double dx = x - island.x;
        double dz = z - island.z;
        double radius = Math.max(1.0, island.radius);
        double radial = Math.sqrt(dx * dx + dz * dz) / radius;

        // Mountains occupy the central ~72%; the outer part stays low and flat.
        if (radial >= 0.78) return 0.0;
        double rim = 1.0 - smoothstep(0.58, 0.78, radial);

        double seed = island.noiseOffset;
        double peak1 = gaussianPeak(x, z, island, seed + 11.0, 0.20, 0.35, 0.58);
        double peak2 = gaussianPeak(x, z, island, seed + 37.0, -0.25, -0.05, 0.78);
        double peak3 = gaussianPeak(x, z, island, seed + 83.0, 0.10, -0.38, 0.42);

        double ridges = Math.max(0.0,
                terrainNoise(x * 0.075, z * 0.075, seed + 1200.0) * 0.75
                        + Math.abs(terrainNoise(x * 0.13, z * 0.13, seed + 2400.0)) * 0.55);

        double peaks = Math.max(peak1, Math.max(peak2, peak3));
        double shape = Math.pow(Math.max(0.0, 1.0 - radial / 0.80), 0.35);
        return Math.max(0.0, peaks * 48.0 + ridges * peaks * 18.0) * rim * shape;
    }

    private double gaussianPeak(int x, int z, Island island, double seed,
                                double px, double pz, double heightFactor) {
        double angle = (seed * 0.00013) % (Math.PI * 2.0);
        double ca = Math.cos(angle), sa = Math.sin(angle);
        double rx = px * island.radius;
        double rz = pz * island.radius;
        double cx = island.x + rx * ca - rz * sa;
        double cz = island.z + rx * sa + rz * ca;
        double dx = x - cx;
        double dz = z - cz;
        double sigma = island.radius * 0.22;
        double d2 = (dx * dx + dz * dz) / Math.max(1.0, sigma * sigma);
        return Math.exp(-d2) * heightFactor;
    }

    private int getBottomHeight(Island island, int x, int z) {
        double distance = horizontalDistance(island, x, z);
        double edge = Math.min(1.0, distance / getEdgeRadius(island, x, z));
        double noise = terrainNoise(x * 0.8, z * 0.8, island.noiseOffset + 8912.0);
        return island.bottomY + (int)Math.round(noise * 2.5 + Math.pow(edge, 2.0) * island.radius * 0.16);
    }

    private boolean isInsideIsland(Island island, int x, int y, int z, int surface, int bottom) {
        if (y > surface || y < bottom) return false;
        double distance = horizontalDistance(island, x, z);
        double edgeRadius = getEdgeRadius(island, x, z);
        double vertical = (double)(y - bottom) / Math.max(1, surface - bottom);
        double shape = 0.045 + Math.pow(vertical, 0.48) * 0.955;
        double rockNoise = terrainNoise(x * 0.9, z * 0.9, island.noiseOffset + y * 0.17);
        double allowed = edgeRadius * shape + rockNoise * island.roughness * 3.5 * (1.0 - vertical);
        return distance <= allowed;
    }

    private double getEdgeRadius(Island island, int x, int z) {
        double angle = Math.atan2(z - island.z, x - island.x);
        double directional = Math.sin(angle * 3.0 + island.noiseOffset) * 0.12
                + Math.sin(angle * 5.0 + island.noiseOffset * 0.31) * 0.08
                + Math.cos(angle * 7.0 - island.noiseOffset * 0.19) * 0.055;
        double worldNoise = terrainNoise(x * 0.45, z * 0.45, island.noiseOffset + 1731.0) * 0.08;
        return island.radius * Math.max(0.62, 1.0 + directional + worldNoise);
    }

    private BlockState getBlockState(Island island, int x, int y, int z, int surface) {
        int depth = surface - y;

        if (island.biomeType == BIOME_MESA) {
            if (depth == 0) return Blocks.RED_SAND.defaultBlockState();
            if (depth <= 2) return mesaColorLayer(y, island.noiseOffset);
            if (depth <= 5) return mesaColorLayer(y, island.noiseOffset + 17.0);
            return mesaColorLayer(y, island.noiseOffset + 31.0);
        }
        if (island.biomeType == BIOME_DESERT) {
            if (depth <= 4) return Blocks.SAND.defaultBlockState();
            if (depth <= 7) return Blocks.SANDSTONE.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_MOUNTAINS) {
            if (depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
            if (depth <= 3) return Blocks.DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_SNOW) {
            if (depth == 0) return Blocks.SNOW_BLOCK.defaultBlockState();
            if (depth <= 3) return Blocks.DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_MUSHROOM) {
            if (depth == 0) return Blocks.MYCELIUM.defaultBlockState();
            if (depth <= 4) return Blocks.DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_ROCKY) {
            if (depth <= 2) return Blocks.COARSE_DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_SAVANNA) {
            if (depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
            if (depth <= 3) return Blocks.COARSE_DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (depth <= 4) return Blocks.DIRT.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    /** Proper Badlands-style colored terracotta bands. */
    private BlockState mesaColorLayer(int y, double offset) {
        int layer = Math.floorMod((int)Math.floor(y + offset * 0.013), 64);
        return switch (layer) {
            case 0, 1, 2, 32, 33, 34 -> Blocks.TERRACOTTA.defaultBlockState();
            case 3, 4, 35, 36 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 5, 6 -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case 7, 8 -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case 9, 10 -> Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
            case 11, 12 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case 13, 14 -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case 15 -> Blocks.MAGENTA_TERRACOTTA.defaultBlockState();
            case 16, 17 -> Blocks.PINK_TERRACOTTA.defaultBlockState();
            case 18, 19 -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case 20, 21 -> Blocks.CYAN_TERRACOTTA.defaultBlockState();
            case 22, 23 -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case 24, 25 -> Blocks.LIME_TERRACOTTA.defaultBlockState();
            case 26, 27 -> Blocks.BLUE_TERRACOTTA.defaultBlockState();
            case 28, 29 -> Blocks.PURPLE_TERRACOTTA.defaultBlockState();
            case 30, 31 -> Blocks.BLACK_TERRACOTTA.defaultBlockState();
            case 37, 38 -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case 39, 40 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 41, 42 -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case 43, 44 -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case 45, 46 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            case 47, 48 -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case 49, 50 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 51, 52 -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case 53, 54 -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case 55, 56 -> Blocks.PURPLE_TERRACOTTA.defaultBlockState();
            case 57, 58 -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case 59, 60 -> Blocks.BROWN_TERRACOTTA.defaultBlockState();
            default -> Blocks.TERRACOTTA.defaultBlockState();
        };
    }

    private double horizontalDistance(Island island, int x, int z) {
        double dx = x - island.x, dz = z - island.z;
        double cos = Math.cos(island.rotation), sin = Math.sin(island.rotation);
        double localX = (dx * cos + dz * sin) / island.stretchX;
        double localZ = (-dx * sin + dz * cos) / island.stretchZ;
        return Math.sqrt(localX * localX + localZ * localZ);
    }

    private double terrainNoise(double x, double z, double offset) {
        return Math.sin(x * 0.035 + offset) * 0.30
                + Math.sin(z * 0.041 + offset * 1.37) * 0.25
                + Math.sin((x + z) * 0.019 + offset * 0.71) * 0.25
                + Math.cos((x - z) * 0.014 - offset * 1.13) * 0.20;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    public int getBiomeTypeForPosition(int x, int z) {
        return getBiomeTypeForPosition(CURRENT_WORLD_SEED, x, z);
    }

    public static long getCurrentWorldSeed() {
        return CURRENT_WORLD_SEED;
    }

    public static int getBiomeTypeForPosition(long seed, int x, int z) {
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
                double mainRadius = spawnCell ? 57.0 : (random.nextDouble() < 0.10 ? 43.0 : 39.0);
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
                    double ir = SMALL_ISLAND_MIN_RADIUS + random.nextDouble() *
                            (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
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

    private static long mixSeed(long seed, long x, long z) {
        long value = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return value;
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk,
                                 StructureTemplateManager structureTemplateManager, ResourceKey<Level> level) {
        // Intentionally empty: custom structures must be placed against generated island terrain,
        // never by the vanilla generator at Y=-64.
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) { }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState randomState, ChunkAccess protoChunk) { }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) { }

    @Override public int getGenDepth() { return 384; }
    @Override public int getMinY() { return WORLD_MIN_Y; }
    @Override public int getSeaLevel() { return 0; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                            LevelHeightAccessor heightAccessor, RandomState randomState) {
        Island island = findIslandAt(findNearbyIslands(x, z), x, z);
        if (island == null) return WORLD_MIN_Y;
        int surface = getSurfaceHeight(island, x, z);
        return surface == Integer.MIN_VALUE ? WORLD_MIN_Y : surface + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor,
                                     RandomState randomState) {
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = Blocks.AIR.defaultBlockState();

        Island island = findIslandAt(findNearbyIslands(x, z), x, z);
        if (island == null) return new NoiseColumn(heightAccessor.getMinY(), states);

        int surface = getSurfaceHeight(island, x, z);
        int bottom = getBottomHeight(island, x, z);
        int minY = heightAccessor.getMinY();
        for (int y = bottom; y <= surface; y++) {
            int index = y - minY;
            if (index < 0 || index >= states.length) continue;
            if (isInsideIsland(island, x, y, z, surface, bottom)) {
                states[index] = getBlockState(island, x, y, z, surface);
            }
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
        result.add("Crocodilandy Sky Generator");
        result.add("Colored Badlands layers + multi-peak mountain islands");
        result.add("Outer mountain rim flattened for a real island silhouette");
        result.add("Vanilla structures disabled pending island-aware placement");
    }

    private record Island(double x, double z, double radius, int surfaceY, int bottomY,
                          double rotation, double stretchX, double stretchZ, double noiseOffset,
                          int biomeType, boolean mainIsland, double roughness) { }
}
