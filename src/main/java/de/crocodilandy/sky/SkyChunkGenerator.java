package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import java.util.Set;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SkyChunkGenerator extends ChunkGenerator {
    public static final MapCodec<SkyChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(BiomeSource.CODEC.fieldOf("biome_source")
                    .forGetter(generator -> generator.biomeSource))
                    .apply(instance, SkyChunkGenerator::new));

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

    private static final int WORLD_MIN_Y = -64;
    private static volatile long CURRENT_WORLD_SEED;

    private long worldSeed;

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
    public CompletableFuture<ChunkAccess> buildTerrain(
            ChunkAccess chunk,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            BiomeManager biomeManager,
            WorldGenRegion carverBiomeRegion,
            Set<Holder<Biome>> possibleBiomes) {
        int startX = chunk.getPos().x() * 16;
        int startZ = chunk.getPos().z() * 16;
        List<SkyIsland> islands = SkyIslandGenerator.findNearby(worldSeed, startX + 8, startZ + 8);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = startX + localX;
                int z = startZ + localZ;
                SkyIsland island = SkyIslandGenerator.findAt(islands, x, z);
                if (island == null) continue;
                fillColumn(chunk, island, x, z);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    private void fillColumn(ChunkAccess chunk, SkyIsland island, int x, int z) {
        int surface = SkyTerrain.surfaceHeight(island, x, z);
        if (surface == Integer.MIN_VALUE) return;

        int bottom = SkyTerrain.bottomHeight(island, x, z);
        for (int y = bottom; y <= surface; y++) {
            if (!SkyTerrain.isInside(island, x, y, z, surface, bottom)) continue;
            chunk.setBlockState(
                    new BlockPos(x, y, z),
                    SkyTerrain.blockState(island, y, surface),
                    0
            );
        }
    }

    public int getBiomeTypeForPosition(int x, int z) {
        return getBiomeTypeForPosition(CURRENT_WORLD_SEED, x, z);
    }

    public static long getCurrentWorldSeed() {
        return CURRENT_WORLD_SEED;
    }

    public static int getBiomeTypeForPosition(long seed, int x, int z) {
        return SkyIslandGenerator.biomeAt(seed, x, z);
    }

    @Override
    public void createStructures(
            RegistryAccess registryAccess,
            ChunkGeneratorStructureState structureState,
            StructureManager structureManager,
            ChunkAccess chunk,
            StructureTemplateManager structureTemplateManager,
            ResourceKey<Level> level) {
        // Custom structures are placed after island terrain generation.
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getMinY() {
        return WORLD_MIN_Y;
    }

    @Override
    public int getSeaLevel() {
        return 0;
    }

    @Override
    public int getBaseHeight(
            int x,
            int z,
            Heightmap.Types type,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {
        SkyIsland island = SkyIslandGenerator.findAt(
                SkyIslandGenerator.findNearby(worldSeed, x, z), x, z
        );
        if (island == null) return WORLD_MIN_Y;

        int surface = SkyTerrain.surfaceHeight(island, x, z);
        return surface == Integer.MIN_VALUE ? WORLD_MIN_Y : surface + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {
        BlockState[] states = new BlockState[heightAccessor.getHeight()];
        for (int i = 0; i < states.length; i++) {
            states[i] = Blocks.AIR.defaultBlockState();
        }

        SkyIsland island = SkyIslandGenerator.findAt(
                SkyIslandGenerator.findNearby(worldSeed, x, z), x, z
        );
        if (island == null) {
            return new NoiseColumn(heightAccessor.getMinY(), states);
        }

        int surface = SkyTerrain.surfaceHeight(island, x, z);
        int bottom = SkyTerrain.bottomHeight(island, x, z);
        int minY = heightAccessor.getMinY();

        for (int y = bottom; y <= surface; y++) {
            int index = y - minY;
            if (index < 0 || index >= states.length) continue;
            if (SkyTerrain.isInside(island, x, y, z, surface, bottom)) {
                states[index] = SkyTerrain.blockState(island, y, surface);
            }
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
        result.add("Crocodilandy Sky Generator");
        result.add("Island terrain delegated to SkyIslandGenerator/SkyTerrain");
        result.add("Custom structures placed after terrain generation");
    }
}
