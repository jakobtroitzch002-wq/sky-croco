package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Blender;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseColumn;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.structure.StructureManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SkyChunkGenerator extends ChunkGenerator {

    public static final MapCodec<SkyChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            BiomeSource.CODEC.fieldOf("biome_source")
                                    .forGetter(generator -> generator.biomeSource)
                    ).apply(instance, SkyChunkGenerator::new)
            );

    private static final int SURFACE_Y = 100;
    private static final int BOTTOM_Y = 45;
    private static final int WORLD_MIN_Y = -64;

    public SkyChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Der deterministische Seed wird aus den Chunk-Koordinaten abgeleitet.
        long seed = mixSeed(chunkX, chunkZ);

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkX * 16 + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = chunkZ * 16 + localZ;

                Island island = findIsland(worldX, worldZ, seed);

                if (island == null) {
                    continue;
                }

                int surfaceY = island.surfaceY;
                int bottomY = island.bottomY;

                for (int y = bottomY; y <= surfaceY; y++) {
                    double depth = (double) (surfaceY - y) / Math.max(1, surfaceY - bottomY);

                    double widthFactor = 1.0 - depth;

                    // Insel wird nach unten schmaler.
                    double radius = island.radius * (0.18 + widthFactor * 0.82);

                    double dx = worldX - island.x;
                    double dz = worldZ - island.z;

                    double distance = Math.sqrt(dx * dx + dz * dz);

                    // Kleine asymmetrische Verformung.
                    double distortion =
                            Math.sin(worldX * 0.085 + island.noiseOffset) * 1.8
                                    + Math.cos(worldZ * 0.071 - island.noiseOffset) * 1.8
                                    + Math.sin((worldX + worldZ) * 0.035) * 2.0;

                    double effectiveRadius = radius + distortion;

                    if (distance <= effectiveRadius) {
                        BlockState state;

                        if (y >= surfaceY - 1) {
                            state = Blocks.GRASS_BLOCK.defaultBlockState();
                        } else if (y >= surfaceY - 4) {
                            state = Blocks.DIRT.defaultBlockState();
                        } else {
                            state = Blocks.STONE.defaultBlockState();
                        }

                        chunk.setBlockState(
                                new BlockPos(worldX, y, worldZ),
                                state,
                                false
                        );
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private Island findIsland(int x, int z, long seed) {

        /*
         * Wir arbeiten mit einem groben Zellraster, aber verschieben
         * die Zentren zufällig. Dadurch entstehen keine perfekten
         * sichtbaren Rasterinseln.
         */
        int cellSize = 160;

        int cellX = Math.floorDiv(x, cellSize);
        int cellZ = Math.floorDiv(z, cellSize);

        Island best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {

                int gx = cellX + ox;
                int gz = cellZ + oz;

                long randomSeed = mixSeed(gx * 73428767L, gz * 912931L) ^ seed;
                RandomSource random = RandomSource.create(randomSeed);

                /*
                 * Nur ein Teil der Zellen bekommt überhaupt eine Insel.
                 * Das erzeugt große Void-Bereiche.
                 */
                if (random.nextFloat() > 0.28f) {
                    continue;
                }

                double centerX =
                        gx * cellSize
                                + cellSize * 0.5
                                + random.nextDouble() * 80.0
                                - 40.0;

                double centerZ =
                        gz * cellSize
                                + cellSize * 0.5
                                + random.nextDouble() * 80.0
                                - 40.0;

                double dx = x - centerX;
                double dz = z - centerZ;

                double distance = Math.sqrt(dx * dx + dz * dz);

                double radius = 10.0 + random.nextDouble() * 40.0;

                if (distance <= radius + 10.0 && distance < bestDistance) {

                    int surfaceY =
                            SURFACE_Y
                                    + random.nextInt(-8, 9);

                    int bottomY =
                            45
                                    + random.nextInt(-3, 8);

                    double offset = random.nextDouble() * 10000.0;

                    best = new Island(
                            centerX,
                            centerZ,
                            radius,
                            surfaceY,
                            bottomY,
                            offset
                    );

                    bestDistance = distance;
                }
            }
        }

        return best;
    }

    private static long mixSeed(long a, long b) {
        long value = a * 0x9E3779B97F4A7C15L;
        value ^= Long.rotateLeft(b * 0xC2B2AE3D27D4EB4FL, 27);
        value *= 0x165667B19E3779F9L;
        value ^= value >>> 29;
        value *= 0x85EBCA77C2B2AE63L;
        value ^= value >>> 32;
        return value;
    }

    @Override
    public void applyCarvers(
            WorldGenRegion region,
            long seed,
            RandomState randomState,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        // Absichtlich leer:
        // keine Höhlen und kein normales Overworld-Terrain.
    }

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        // Die Oberfläche wird bereits in fillFromNoise gesetzt.
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
        // Später können wir hier Vanilla-Mobspawns aktivieren.
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
        // Wir möchten keine normale Wasserwelt.
        return 0;
    }

    @Override
    public int getBaseHeight(
            int x,
            int z,
            Heightmap.Types type,
            net.minecraft.world.level.LevelHeightAccessor heightAccessor,
            RandomState randomState
    ) {
        Island island = findIsland(x, z, 0L);

        return island == null ? WORLD_MIN_Y : island.surfaceY + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(
            int x,
            int z,
            net.minecraft.world.level.LevelHeightAccessor heightAccessor,
            RandomState randomState
    ) {
        BlockState[] states =
                new BlockState[heightAccessor.getHeight()];

        for (int i = 0; i < states.length; i++) {
            states[i] = Blocks.AIR.defaultBlockState();
        }

        Island island = findIsland(x, z, 0L);

        if (island != null) {
            int minY = heightAccessor.getMinBuildHeight();

            for (int y = island.bottomY; y <= island.surfaceY; y++) {
                int index = y - minY;

                if (index < 0 || index >= states.length) {
                    continue;
                }

                if (y >= island.surfaceY - 1) {
                    states[index] = Blocks.GRASS_BLOCK.defaultBlockState();
                } else if (y >= island.surfaceY - 4) {
                    states[index] = Blocks.DIRT.defaultBlockState();
                } else {
                    states[index] = Blocks.STONE.defaultBlockState();
                }
            }
        }

        return new NoiseColumn(
                heightAccessor.getMinBuildHeight(),
                states
        );
    }

    @Override
    public void addDebugScreenInfo(
            List<String> result,
            RandomState randomState,
            BlockPos feetPos
    ) {
        result.add("Crocodilandy Sky Generator");
        result.add("Surface Y: " + SURFACE_Y);
        result.add("Void world");
    }

    private record Island(
            double x,
            double z,
            double radius,
            int surfaceY,
            int bottomY,
            double noiseOffset
    ) {
    }
}
