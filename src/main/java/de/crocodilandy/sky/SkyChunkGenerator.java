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

    public static final MapCodec<SkyChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            BiomeSource.CODEC.fieldOf("biome_source")
                                    .forGetter(generator -> generator.biomeSource)
                    ).apply(instance, SkyChunkGenerator::new)
            );

    private static final int WORLD_MIN_Y = -64;
    private static final int SURFACE_Y = 105;

    /*
     * Abstand zwischen Inselgruppen.
     *
     * Eine Gruppe pro ca. 700x700-Blöcke-Zelle.
     * Durch den Versatz und die garantierte Gruppe
     * sind die Inseln sichtbar, aber nicht zu häufig.
     */
    private static final int GROUP_CELL_SIZE = 700;

    /*
     * Inselgruppe:
     * genau eine größere Hauptinsel und viele kleine Begleitinseln.
     */
    private static final int MIN_SMALL_ISLANDS = 5;
    private static final int MAX_SMALL_ISLANDS = 10;

    private static final double MAIN_ISLAND_MIN_RADIUS = 38.0;
    private static final double MAIN_ISLAND_MAX_RADIUS = 58.0;

    private static final double SMALL_ISLAND_MIN_RADIUS = 8.0;
    private static final double SMALL_ISLAND_MAX_RADIUS = 24.0;

    private static final double GROUP_RADIUS = 170.0;

    private long worldSeed = 0L;

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
            long seed
    ) {
        this.worldSeed = seed;

        return super.createState(
                structureSetLookup,
                randomState,
                seed
        );
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();

        int worldStartX = chunkX * 16;
        int worldStartZ = chunkZ * 16;

        List<Island> islands =
                findNearbyIslands(
                        worldStartX + 8,
                        worldStartZ + 8
                );

        for (int localX = 0; localX < 16; localX++) {
            int worldX = worldStartX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {
                int worldZ = worldStartZ + localZ;

                Island bestIsland =
                        findIslandAt(
                                islands,
                                worldX,
                                worldZ
                        );

                if (bestIsland == null) {
                    continue;
                }

                int surface =
                        getSurfaceHeight(
                                bestIsland,
                                worldX,
                                worldZ
                        );

                if (surface == Integer.MIN_VALUE) {
                    continue;
                }

                int bottom =
                        getBottomHeight(
                                bestIsland,
                                worldX,
                                worldZ
                        );

                for (int y = bottom; y <= surface; y++) {

                    if (!isInsideIsland(
                            bestIsland,
                            worldX,
                            y,
                            worldZ,
                            surface,
                            bottom
                    )) {
                        continue;
                    }

                    BlockState state =
                            getBlockState(
                                    bestIsland,
                                    worldX,
                                    y,
                                    worldZ,
                                    surface
                            );

                    chunk.setBlockState(
                            new BlockPos(
                                    worldX,
                                    y,
                                    worldZ
                            ),
                            state,
                            0
                    );
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    private List<Island> findNearbyIslands(
            int x,
            int z
    ) {
        List<Island> result = new ArrayList<>();

        int cellX =
                Math.floorDiv(
                        x,
                        GROUP_CELL_SIZE
                );

        int cellZ =
                Math.floorDiv(
                        z,
                        GROUP_CELL_SIZE
                );

        /*
         * 5x5 statt 3x3, damit auch große Gruppen,
         * die nahe einer Zellgrenze liegen,
         * zuverlässig gefunden werden.
         */
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {

                int groupX = cellX + offsetX;
                int groupZ = cellZ + offsetZ;

                long cellSeed =
                        mixSeed(
                                worldSeed,
                                groupX,
                                groupZ
                        );

                RandomSource random =
                        RandomSource.create(cellSeed);

                /*
                 * Jede Zelle erzeugt eine Inselgruppe.
                 * Der große Versatz verhindert ein sichtbares Raster.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 220.0
                                - 110.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 220.0
                                - 110.0;

                int biomeType =
                        random.nextInt(8);

                /*
                 * Eine große Hauptinsel.
                 */
                double mainRadius =
                        MAIN_ISLAND_MIN_RADIUS
                                + random.nextDouble()
                                * (
                                MAIN_ISLAND_MAX_RADIUS
                                        - MAIN_ISLAND_MIN_RADIUS
                        );

                int mainSurface =
                        SURFACE_Y
                                + random.nextInt(
                                -8,
                                9
                        );

                int mainBottom =
                        30
                                + random.nextInt(
                                18
                        );

                result.add(
                        createIsland(
                                random,
                                centerX,
                                centerZ,
                                mainRadius,
                                mainSurface,
                                mainBottom,
                                biomeType,
                                true
                        )
                );

                /*
                 * Viele kleine Inseln rund um die Hauptinsel.
                 */
                int islandCount =
                        MIN_SMALL_ISLANDS
                                + random.nextInt(
                                MAX_SMALL_ISLANDS
                                        - MIN_SMALL_ISLANDS
                                        + 1
                        );

                for (int i = 0; i < islandCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double distance =
                            45.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    0.75
                            )
                                    * GROUP_RADIUS;

                    double islandX =
                            centerX
                                    + Math.cos(angle)
                                    * distance;

                    double islandZ =
                            centerZ
                                    + Math.sin(angle)
                                    * distance;

                    double radius =
                            SMALL_ISLAND_MIN_RADIUS
                                    + random.nextDouble()
                                    * (
                                    SMALL_ISLAND_MAX_RADIUS
                                            - SMALL_ISLAND_MIN_RADIUS
                            );

                    int surface =
                            SURFACE_Y
                                    + random.nextInt(
                                    -10,
                                    11
                            );

                    int bottom =
                            surface
                                    - 25
                                    - random.nextInt(
                                    22
                            );

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    surface,
                                    bottom,
                                    biomeType,
                                    false
                            )
                    );
                }
            }
        }

        return result;
    }

    private Island createIsland(
            RandomSource random,
            double x,
            double z,
            double radius,
            int surfaceY,
            int bottomY,
            int biomeType,
            boolean mainIsland
    ) {
        return new Island(
                x,
                z,
                radius,
                surfaceY,
                bottomY,
                random.nextDouble() * Math.PI * 2.0,
                0.75 + random.nextDouble() * 0.55,
                0.75 + random.nextDouble() * 0.55,
                random.nextDouble() * 100000.0,
                biomeType,
                mainIsland,
                0.55 + random.nextDouble() * 0.8
        );
    }

    private Island findIslandAt(
            List<Island> islands,
            int x,
            int z
    ) {
        Island best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Island island : islands) {

            double distance =
                    horizontalDistance(
                            island,
                            x,
                            z
                    );

            double edgeRadius =
                    getEdgeRadius(
                            island,
                            x,
                            z
                    );

            if (distance > edgeRadius) {
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                best = island;
            }
        }

        return best;
    }

    private int getSurfaceHeight(
            Island island,
            int x,
            int z
    ) {
        double distance =
                horizontalDistance(
                        island,
                        x,
                        z
                );

        double edgeRadius =
                getEdgeRadius(
                        island,
                        x,
                        z
                );

        if (distance > edgeRadius) {
            return Integer.MIN_VALUE;
        }

        double edge =
                distance / edgeRadius;

        double noise =
                terrainNoise(
                        x,
                        z,
                        island.noiseOffset
                );

        double detail =
                terrainNoise(
                        x * 2.2,
                        z * 2.2,
                        island.noiseOffset + 734.0
                );

        /*
         * Allgemeine natürliche Form.
         */
        double terrain =
                noise * 4.0
                        + detail * 2.5;

        /*
         * Große Steininseln bekommen richtige Berge.
         */
        if (island.mainIsland
                && island.biomeType == 2) {

            double mountain =
                    mountainNoise(
                            x,
                            z,
                            island
                    );

            terrain += mountain;
        }

        /*
         * Taiga und Forest bekommen etwas hügeligere Oberflächen.
         */
        if (island.biomeType == 1
                || island.biomeType == 4) {

            terrain +=
                    terrainNoise(
                            x * 0.55,
                            z * 0.55,
                            island.noiseOffset + 3300.0
                    ) * 6.0;
        }

        /*
         * Desert eher flacher.
         */
        if (island.biomeType == 3) {
            terrain *= 0.55;
        }

        /*
         * Rand leicht abfallend.
         */
        double broadShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - edge
                        ),
                        2.0
                )
                        * island.radius
                        * 0.06;

        return island.surfaceY
                + (int) Math.round(
                terrain
                        + broadShape
        );
    }

    private int getBottomHeight(
            Island island,
            int x,
            int z
    ) {
        double distance =
                horizontalDistance(
                        island,
                        x,
                        z
                );

        double edgeRadius =
                getEdgeRadius(
                        island,
                        x,
                        z
                );

        double edge =
                Math.min(
                        1.0,
                        distance / edgeRadius
                );

        /*
         * Organische Variation an der Unterseite.
         */
        double noise =
                terrainNoise(
                        x * 0.8,
                        z * 0.8,
                        island.noiseOffset + 8912.0
                );

        /*
         * Die Insel wird nach außen hin etwas dünner,
         * aber ohne komische gerade abgeschnittene Teile.
         */
        double bottomRaise =
                Math.pow(
                        edge,
                        2.0
                )
                        * island.radius
                        * 0.18;

        return island.bottomY
                + (int) Math.round(
                noise * 3.0
                        + bottomRaise
        );
    }

    private boolean isInsideIsland(
            Island island,
            int x,
            int y,
            int z,
            int surface,
            int bottom
    ) {
        if (y > surface || y < bottom) {
            return false;
        }

        double distance =
                horizontalDistance(
                        island,
                        x,
                        z
                );

        double edgeRadius =
                getEdgeRadius(
                        island,
                        x,
                        z
                );

        double vertical =
                (double) (y - bottom)
                        / Math.max(
                        1,
                        surface - bottom
                );

        /*
         * Sehr schnell spitz nach unten.
         *
         * Oben fast voller Radius,
         * unten nur ein kleiner Kern.
         */
        double shape =
                0.05
                        + Math.pow(
                        vertical,
                        0.48
                ) * 0.95;

        /*
         * Felsige organische Ausfransungen,
         * besonders an der Unterseite.
         */
        double rockNoise =
                terrainNoise(
                        x * 0.9,
                        z * 0.9,
                        island.noiseOffset
                                + y * 0.17
                );

        double allowedRadius =
                edgeRadius
                        * shape
                        + rockNoise
                        * island.roughness
                        * 4.0
                        * (1.0 - vertical);

        return distance <= allowedRadius;
    }

    private double getEdgeRadius(
            Island island,
            int x,
            int z
    ) {
        double angle =
                Math.atan2(
                        z - island.z,
                        x - island.x
                );

        /*
         * Mehrere Frequenzen verhindern perfekte Kreise.
         */
        double n1 =
                Math.sin(
                        angle * 3.0
                                + island.noiseOffset
                );

        double n2 =
                Math.sin(
                        angle * 5.0
                                + island.noiseOffset * 0.31
                );

        double n3 =
                Math.cos(
                        angle * 7.0
                                - island.noiseOffset * 0.19
                );

        double directional =
                n1 * 0.13
                        + n2 * 0.09
                        + n3 * 0.06;

        double worldNoise =
                terrainNoise(
                        x * 0.45,
                        z * 0.45,
                        island.noiseOffset + 1731.0
                )
                        * 0.10;

        return island.radius
                * Math.max(
                0.62,
                1.0
                        + directional
                        + worldNoise
        );
    }

    private double mountainNoise(
            int x,
            int z,
            Island island
    ) {
        double dx = x - island.x;
        double dz = z - island.z;

        double distance =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                );

        double normalized =
                distance / Math.max(
                        1.0,
                        island.radius
                );

        if (normalized > 0.95) {
            return 0.0;
        }

        double n1 =
                terrainNoise(
                        x * 0.24,
                        z * 0.24,
                        island.noiseOffset + 1200.0
                );

        double n2 =
                terrainNoise(
                        x * 0.48,
                        z * 0.48,
                        island.noiseOffset + 3400.0
                );

        /*
         * Nur einige Bereiche werden zu hohen Bergen.
         */
        double ridge =
                Math.max(
                        0.0,
                        n1 * 0.7
                                + n2 * 0.45
                );

        double center =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - normalized
                        ),
                        0.8
                );

        return ridge
                * center
                * 30.0;
    }

    private BlockState getBlockState(
            Island island,
            int x,
            int y,
            int z,
            int surface
    ) {
        int depth =
                surface - y;

        /*
         * Mesa:
         * horizontale farbige Terracotta-Schichten.
         */
        if (island.biomeType == 0) {

            if (depth <= 1) {
                return Blocks.RED_SAND.defaultBlockState();
            }

            if (depth <= 4) {
                return Blocks.TERRACOTTA.defaultBlockState();
            }

            return getMesaTerracotta(
                    y,
                    island.noiseOffset
            );
        }

        /*
         * Stein-/Berginsel.
         */
        if (island.biomeType == 2) {

            if (depth == 0) {
                return Blocks.GRASS_BLOCK.defaultBlockState();
            }

            if (depth <= 3) {
                return Blocks.DIRT.defaultBlockState();
            }

            double stoneNoise =
                    terrainNoise(
                            x,
                            z,
                            island.noiseOffset + 992.0
                    );

            if (stoneNoise > 0.42) {
                return Blocks.GRAVEL.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Wüsteninsel.
         */
        if (island.biomeType == 3) {

            if (depth <= 4) {
                return Blocks.SAND.defaultBlockState();
            }

            if (depth <= 7) {
                return Blocks.SANDSTONE.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Schneeinsel.
         */
        if (island.biomeType == 5) {

            if (depth == 0) {
                return Blocks.SNOW_BLOCK.defaultBlockState();
            }

            if (depth <= 3) {
                return Blocks.DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Pilzinsel.
         */
        if (island.biomeType == 6) {

            if (depth == 0) {
                return Blocks.MYCELIUM.defaultBlockState();
            }

            if (depth <= 4) {
                return Blocks.DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Badlands-artige Steininsel.
         */
        if (island.biomeType == 7) {

            if (depth <= 2) {
                return Blocks.COARSE_DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Plains, Forest und Taiga.
         */
        if (depth == 0) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (depth <= 4) {
            return Blocks.DIRT.defaultBlockState();
        }

        return Blocks.STONE.defaultBlockState();
    }

    private BlockState getMesaTerracotta(
            int y,
            double offset
    ) {
        /*
         * Farbige horizontale Mesa-Schichten.
         *
         * Über die Y-Höhe entstehen richtige Ringe
         * und nicht nur eine einzelne Terracotta-Farbe.
         */
        int layer =
                Math.floorMod(
                        (int) Math.floor(
                                y
                                        + offset * 0.013
                                        + Math.sin(
                                        y * 0.31
                                ) * 2.0
                        ),
                        24
                );

        if (layer <= 1) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 3) {
            return Blocks.RED_SAND.defaultBlockState();
        }

        if (layer <= 5) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 7) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 9) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 11) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 13) {
            return Blocks.RED_SAND.defaultBlockState();
        }

        if (layer <= 16) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer <= 19) {
            return Blocks.RED_SAND.defaultBlockState();
        }

        return Blocks.TERRACOTTA.defaultBlockState();
    }

    private double horizontalDistance(
            Island island,
            int x,
            int z
    ) {
        double dx = x - island.x;
        double dz = z - island.z;

        double cos =
                Math.cos(
                        island.rotation
                );

        double sin =
                Math.sin(
                        island.rotation
                );

        double localX =
                dx * cos
                        + dz * sin;

        double localZ =
                -dx * sin
                        + dz * cos;

        localX /= island.stretchX;
        localZ /= island.stretchZ;

        return Math.sqrt(
                localX * localX
                        + localZ * localZ
        );
    }

    private double terrainNoise(
            double x,
            double z,
            double offset
    ) {
        double a =
                Math.sin(
                        x * 0.035
                                + offset
                );

        double b =
                Math.sin(
                        z * 0.041
                                + offset * 1.37
                );

        double c =
                Math.sin(
                        (x + z) * 0.019
                                + offset * 0.71
                );

        double d =
                Math.cos(
                        (x - z) * 0.014
                                - offset * 1.13
                );

        return a * 0.30
                + b * 0.25
                + c * 0.25
                + d * 0.20;
    }

    private static long mixSeed(
            long seed,
            long x,
            long z
    ) {
        long value =
                seed
                        ^ (x * 0x9E3779B97F4A7C15L)
                        ^ (z * 0xC2B2AE3D27D4EB4FL);

        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;

        return value;
    }

    /*
     * Keine Vanilla-Strukturen.
     */
    @Override
    public void createStructures(
            RegistryAccess registryAccess,
            ChunkGeneratorStructureState structureState,
            StructureManager structureManager,
            ChunkAccess chunk,
            StructureTemplateManager structureTemplateManager,
            ResourceKey<Level> level
    ) {
    }

    /*
     * Keine Höhlen oder Carver.
     */
    @Override
    public void applyCarvers(
            WorldGenRegion region,
            long seed,
            RandomState randomState,
            BiomeManager biomeManager,
            StructureManager structureManager,
            ChunkAccess chunk
    ) {
    }

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
    }

    @Override
    public void spawnOriginalMobs(
            WorldGenRegion worldGenRegion
    ) {
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
            RandomState randomState
    ) {
        List<Island> islands =
                findNearbyIslands(
                        x,
                        z
                );

        Island island =
                findIslandAt(
                        islands,
                        x,
                        z
                );

        if (island == null) {
            return WORLD_MIN_Y;
        }

        int surface =
                getSurfaceHeight(
                        island,
                        x,
                        z
                );

        if (surface == Integer.MIN_VALUE) {
            return WORLD_MIN_Y;
        }

        return surface + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(
            int x,
            int z,
            LevelHeightAccessor heightAccessor,
            RandomState randomState
    ) {
        BlockState[] states =
                new BlockState[
                        heightAccessor.getHeight()
                ];

        for (int i = 0; i < states.length; i++) {
            states[i] =
                    Blocks.AIR.defaultBlockState();
        }

        List<Island> islands =
                findNearbyIslands(
                        x,
                        z
                );

        Island island =
                findIslandAt(
                        islands,
                        x,
                        z
                );

        if (island == null) {
            return new NoiseColumn(
                    heightAccessor.getMinY(),
                    states
            );
        }

        int surface =
                getSurfaceHeight(
                        island,
                        x,
                        z
                );

        if (surface == Integer.MIN_VALUE) {
            return new NoiseColumn(
                    heightAccessor.getMinY(),
                    states
            );
        }

        int bottom =
                getBottomHeight(
                        island,
                        x,
                        z
                );

        int minY =
                heightAccessor.getMinY();

        for (int y = bottom; y <= surface; y++) {

            int index =
                    y - minY;

            if (index < 0
                    || index >= states.length) {
                continue;
            }

            if (!isInsideIsland(
                    island,
                    x,
                    y,
                    z,
                    surface,
                    bottom
            )) {
                continue;
            }

            states[index] =
                    getBlockState(
                            island,
                            x,
                            y,
                            z,
                            surface
                    );
        }

        return new NoiseColumn(
                minY,
                states
        );
    }

    @Override
    public void addDebugScreenInfo(
            List<String> result,
            RandomState randomState,
            BlockPos feetPos
    ) {
        result.add(
                "Crocodilandy Sky Generator"
        );

        result.add(
                "Island groups: ~700 blocks"
        );

        result.add(
                "1 large island + 5-10 small islands"
        );

        result.add(
                "Mesa, Forest, Mountains, Desert, Plains, Snow, Mushroom, Rocky"
        );

        result.add(
                "Structures: disabled"
        );
    }

    private record Island(
            double x,
            double z,
            double radius,
            int surfaceY,
            int bottomY,
            double rotation,
            double stretchX,
            double stretchZ,
            double noiseOffset,
            int biomeType,
            boolean mainIsland,
            double roughness
    ) {
    }
}
