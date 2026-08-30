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
    private static final double MAIN_ISLAND_MIN_RADIUS = 28.0;
    private static final double MAIN_ISLAND_MAX_RADIUS = 42.0;
    private static final double SMALL_ISLAND_MIN_RADIUS = 5.0;
    private static final double SMALL_ISLAND_MAX_RADIUS = 15.0;
    private static final double GROUP_RADIUS = 125.0;

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

    private long worldSeed = 0L;
    private static volatile long CURRENT_WORLD_SEED = 0L;

    public SkyChunkGenerator(BiomeSource biomeSource) { super(biomeSource); }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<net.minecraft.world.level.levelgen.structure.StructureSet> structureSetLookup,
            RandomState randomState, long seed) {
        this.worldSeed = seed;
        CURRENT_WORLD_SEED = seed;
        return super.createState(structureSetLookup, randomState, seed);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        int worldStartX = chunk.getPos().x() * 16;
        int worldStartZ = chunk.getPos().z() * 16;
        List<Island> islands = findNearbyIslands(worldStartX + 8, worldStartZ + 8);

        for (int localX = 0; localX < 16; localX++) {
            int worldX = worldStartX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = worldStartZ + localZ;
                Island island = findIslandAt(islands, worldX, worldZ);
                if (island == null) continue;
                int surface = getSurfaceHeight(island, worldX, worldZ);
                if (surface == Integer.MIN_VALUE) continue;
                int bottom = getBottomHeight(island, worldX, worldZ);
                for (int y = bottom; y <= surface; y++) {
                    if (!isInsideIsland(island, worldX, y, worldZ, surface, bottom)) continue;
                    chunk.setBlockState(new BlockPos(worldX, y, worldZ),
                            getBlockState(island, worldX, y, worldZ, surface), 0);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private List<Island> findNearbyIslands(int x, int z) {
        List<Island> result = new ArrayList<>();
        int cellX = Math.floorDiv(x, GROUP_CELL_SIZE);
        int cellZ = Math.floorDiv(z, GROUP_CELL_SIZE);

        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int groupX = cellX + offsetX;
                int groupZ = cellZ + offsetZ;
                RandomSource random = RandomSource.create(mixSeed(worldSeed, groupX, groupZ));
                boolean spawnCell = groupX == 0 && groupZ == 0;
                if (!spawnCell && random.nextDouble() > 0.38) continue;

                double centerX = groupX * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                double centerZ = groupZ * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0
                        + random.nextDouble() * 620.0 - 310.0;
                int biomeType = random.nextInt(BIOME_COUNT);

                double mainRadius = spawnCell ? 48.0 + random.nextDouble() * 14.0
                        : random.nextDouble() < 0.12 ? 38.0 + random.nextDouble() * 12.0
                        : MAIN_ISLAND_MIN_RADIUS + random.nextDouble() *
                        (MAIN_ISLAND_MAX_RADIUS - MAIN_ISLAND_MIN_RADIUS);

                int mainSurface = SURFACE_Y + random.nextInt(-8, 9);
                int mainBottom = 30 + random.nextInt(18);
                result.add(createIsland(random, centerX, centerZ, mainRadius,
                        mainSurface, mainBottom, biomeType, true));

                int islandCount = MIN_SMALL_ISLANDS +
                        random.nextInt(MAX_SMALL_ISLANDS - MIN_SMALL_ISLANDS + 1);
                for (int i = 0; i < islandCount; i++) {
                    double angle = random.nextDouble() * Math.PI * 2.0;
                    double distance = 38.0 + Math.pow(random.nextDouble(), 0.75) * GROUP_RADIUS;
                    double islandX = centerX + Math.cos(angle) * distance;
                    double islandZ = centerZ + Math.sin(angle) * distance;
                    double radius = SMALL_ISLAND_MIN_RADIUS + random.nextDouble() *
                            (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
                    int surface = SURFACE_Y + random.nextInt(-10, 11);
                    int bottom = surface - 25 - random.nextInt(22);
                    result.add(createIsland(random, islandX, islandZ, radius,
                            surface, bottom, biomeType, false));
                }
            }
        }
        return result;
    }

    private Island createIsland(RandomSource random, double x, double z, double radius,
                                int surfaceY, int bottomY, int biomeType, boolean mainIsland) {
        return new Island(x, z, radius, surfaceY, bottomY,
                random.nextDouble() * Math.PI * 2.0,
                0.75 + random.nextDouble() * 0.55,
                0.75 + random.nextDouble() * 0.55,
                random.nextDouble() * 100000.0,
                biomeType, mainIsland, 0.55 + random.nextDouble() * 0.8);
    }

    private Island findIslandAt(List<Island> islands, int x, int z) {
        Island best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Island island : islands) {
            double distance = horizontalDistance(island, x, z);
            if (distance > getEdgeRadius(island, x, z)) continue;
            if (distance < bestDistance) { bestDistance = distance; best = island; }
        }
        return best;
    }

    private int getSurfaceHeight(Island island, int x, int z) {
        double distance = horizontalDistance(island, x, z);
        double edgeRadius = getEdgeRadius(island, x, z);
        if (distance > edgeRadius) return Integer.MIN_VALUE;
        double edge = distance / edgeRadius;
        double terrain = terrainNoise(x, z, island.noiseOffset) * 4.0
                + terrainNoise(x * 2.2, z * 2.2, island.noiseOffset + 734.0) * 2.5;

        if (island.biomeType == BIOME_MOUNTAINS) terrain += mountainTerrain(x, z, island);
        if (island.biomeType == BIOME_TAIGA || island.biomeType == BIOME_FOREST
                || island.biomeType == BIOME_JUNGLE || island.biomeType == BIOME_BIRCH
                || island.biomeType == BIOME_CHERRY) {
            terrain += terrainNoise(x * 0.55, z * 0.55, island.noiseOffset + 3300.0) * 7.0;
        }
        if (island.biomeType == BIOME_ROCKY || island.biomeType == BIOME_SAVANNA) {
            terrain += terrainNoise(x * 0.45, z * 0.45, island.noiseOffset + 4500.0) * 9.0;
        }
        if (island.biomeType == BIOME_DESERT || island.biomeType == BIOME_MESA) terrain *= 0.55;
        if (island.biomeType == BIOME_SWAMP) terrain *= 0.45;

        double broadShape = Math.pow(Math.max(0.0, 1.0 - edge), 2.0) * island.radius * 0.06;
        return island.surfaceY + (int)Math.round(terrain + broadShape);
    }

    private double mountainTerrain(int x, int z, Island island) {
        double dx = x - island.x, dz = z - island.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double normalized = distance / Math.max(1.0, island.radius);
        if (normalized > 1.05) return 0.0;

        double large = terrainNoise(x * 0.075, z * 0.075, island.noiseOffset + 1200.0);
        double ridge = Math.abs(terrainNoise(x * 0.12, z * 0.12, island.noiseOffset + 2400.0));
        double detail = terrainNoise(x * 0.30, z * 0.30, island.noiseOffset + 3400.0);
        double center = Math.pow(Math.max(0.0, 1.0 - normalized), 0.65);
        double peak = Math.max(0.0, large * 0.65 + ridge * 0.75 + detail * 0.25);
        return peak * center * 55.0;
    }

    private int getBottomHeight(Island island, int x, int z) {
        double distance = horizontalDistance(island, x, z);
        double edge = Math.min(1.0, distance / getEdgeRadius(island, x, z));
        double noise = terrainNoise(x * 0.8, z * 0.8, island.noiseOffset + 8912.0);
        return island.bottomY + (int)Math.round(noise * 3.0 + Math.pow(edge, 2.0) * island.radius * 0.18);
    }

    private boolean isInsideIsland(Island island, int x, int y, int z, int surface, int bottom) {
        if (y > surface || y < bottom) return false;
        double distance = horizontalDistance(island, x, z);
        double edgeRadius = getEdgeRadius(island, x, z);
        double vertical = (double)(y - bottom) / Math.max(1, surface - bottom);
        double shape = 0.05 + Math.pow(vertical, 0.48) * 0.95;
        double rockNoise = terrainNoise(x * 0.9, z * 0.9, island.noiseOffset + y * 0.17);
        double allowedRadius = edgeRadius * shape + rockNoise * island.roughness * 4.0 * (1.0 - vertical);
        return distance <= allowedRadius;
    }

    private double getEdgeRadius(Island island, int x, int z) {
        double angle = Math.atan2(z - island.z, x - island.x);
        double directional = Math.sin(angle * 3.0 + island.noiseOffset) * 0.13
                + Math.sin(angle * 5.0 + island.noiseOffset * 0.31) * 0.09
                + Math.cos(angle * 7.0 - island.noiseOffset * 0.19) * 0.06;
        double worldNoise = terrainNoise(x * 0.45, z * 0.45, island.noiseOffset + 1731.0) * 0.10;
        return island.radius * Math.max(0.62, 1.0 + directional + worldNoise);
    }

    private BlockState getBlockState(Island island, int x, int y, int z, int surface) {
        int depth = surface - y;
        if (island.biomeType == BIOME_MESA) {
            if (depth <= 1) return Blocks.RED_SAND.defaultBlockState();
            if (depth <= 4) return Blocks.TERRACOTTA.defaultBlockState();
            return getMesaTerracotta(y, island.noiseOffset);
        }
        if (island.biomeType == BIOME_MOUNTAINS) {
            if (depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
            if (depth <= 3) return Blocks.DIRT.defaultBlockState();
            return terrainNoise(x, z, island.noiseOffset + 992.0) > 0.42
                    ? Blocks.GRAVEL.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_DESERT) {
            if (depth <= 4) return Blocks.SAND.defaultBlockState();
            if (depth <= 7) return Blocks.SANDSTONE.defaultBlockState();
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
        if (island.biomeType == BIOME_ROCKY || island.biomeType == BIOME_SAVANNA) {
            if (depth <= 2) return Blocks.COARSE_DIRT.defaultBlockState();
            return Blocks.STONE.defaultBlockState();
        }
        if (island.biomeType == BIOME_SWAMP && depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (depth == 0) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (depth <= 4) return Blocks.DIRT.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }

    private BlockState getMesaTerracotta(int y, double offset) {
        int layer = Math.floorMod((int)Math.floor(y + offset * 0.013 + Math.sin(y * 0.31) * 2.0), 24);
        if (layer <= 1) return Blocks.TERRACOTTA.defaultBlockState();
        if (layer <= 3) return Blocks.RED_SAND.defaultBlockState();
        if (layer <= 13) return Blocks.TERRACOTTA.defaultBlockState();
        if (layer <= 16) return Blocks.RED_SAND.defaultBlockState();
        if (layer <= 19) return Blocks.TERRACOTTA.defaultBlockState();
        return Blocks.TERRACOTTA.defaultBlockState();
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

    public int getBiomeTypeForPosition(int x, int z) { return getBiomeTypeForPosition(CURRENT_WORLD_SEED, x, z); }
    public static long getCurrentWorldSeed() { return CURRENT_WORLD_SEED; }

    /** Returns -1 in empty sky. */
    public static int getBiomeTypeForPosition(long seed, int x, int z) {
        int cellX = Math.floorDiv(x, GROUP_CELL_SIZE), cellZ = Math.floorDiv(z, GROUP_CELL_SIZE);
        int bestBiome = -1; double bestDistance = Double.MAX_VALUE;
        for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) {
            int gx = cellX + ox, gz = cellZ + oz;
            RandomSource random = RandomSource.create(mixSeed(seed, gx, gz));
            boolean spawnCell = gx == 0 && gz == 0;
            if (!spawnCell && random.nextDouble() > 0.38) continue;
            double cx = gx * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0 + random.nextDouble() * 620.0 - 310.0;
            double cz = gz * GROUP_CELL_SIZE + GROUP_CELL_SIZE / 2.0 + random.nextDouble() * 620.0 - 310.0;
            int biome = random.nextInt(BIOME_COUNT);
            double radius = spawnCell ? 62.0 : random.nextDouble() < 0.12 ? 50.0 : 42.0;
            double d = Math.hypot(x - cx, z - cz);
            if (d <= radius * 1.70 && d < bestDistance) { bestDistance = d; bestBiome = biome; }
            int count = MIN_SMALL_ISLANDS + random.nextInt(MAX_SMALL_ISLANDS - MIN_SMALL_ISLANDS + 1);
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double dist = 38.0 + Math.pow(random.nextDouble(), 0.75) * GROUP_RADIUS;
                double ix = cx + Math.cos(angle) * dist, iz = cz + Math.sin(angle) * dist;
                double ir = SMALL_ISLAND_MIN_RADIUS + random.nextDouble() * (SMALL_ISLAND_MAX_RADIUS - SMALL_ISLAND_MIN_RADIUS);
                d = Math.hypot(x - ix, z - iz);
                if (d <= ir * 1.70 && d < bestDistance) { bestDistance = d; bestBiome = biome; }
            }
        }
        return bestBiome;
    }

    private static long mixSeed(long seed, long x, long z) {
        long value = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        value ^= value >>> 30; value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27; value *= 0x94D049BB133111EBL;
        value ^= value >>> 31; return value;
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk,
                                 StructureTemplateManager structureTemplateManager, ResourceKey<Level> level) {
        // Disabled for the custom Sky world. Phase 4 will place only validated structures on islands.
    }

    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                                       BiomeManager biomeManager, StructureManager structureManager, ChunkAccess chunk) { }
    @Override public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                                       RandomState randomState, ChunkAccess protoChunk) { }
    @Override public void spawnOriginalMobs(WorldGenRegion worldGenRegion) { }
    @Override public int getGenDepth() { return 384; }
    @Override public int getMinY() { return WORLD_MIN_Y; }
    @Override public int getSeaLevel() { return 0; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        Island island = findIslandAt(findNearbyIslands(x, z), x, z);
        if (island == null) return WORLD_MIN_Y;
        int surface = getSurfaceHeight(island, x, z);
        return surface == Integer.MIN_VALUE ? WORLD_MIN_Y : surface + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < states.length; i++) states[i] = Blocks.AIR.defaultBlockState();
        Island island = findIslandAt(findNearbyIslands(x, z), x, z);
        if (island == null) return new NoiseColumn(heightAccessor.getMinY(), states);
        int surface = getSurfaceHeight(island, x, z);
        if (surface == Integer.MIN_VALUE) return new NoiseColumn(heightAccessor.getMinY(), states);
        int bottom = getBottomHeight(island, x, z), minY = heightAccessor.getMinY();
        for (int y = bottom; y <= surface; y++) {
            int index = y - minY;
            if (index < 0 || index >= states.length) continue;
            if (!isInsideIsland(island, x, y, z, surface, bottom)) continue;
            states[index] = getBlockState(island, x, y, z, surface);
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
        result.add("Crocodilandy Sky Generator");
        result.add("Custom terrain: mountains, valleys and chaotic islands");
        result.add("Structures disabled for safe custom placement");
    }

    private record Island(double x, double z, double radius, int surfaceY, int bottomY,
                          double rotation, double stretchX, double stretchZ, double noiseOffset,
                          int biomeType, boolean mainIsland, double roughness) { }
}
