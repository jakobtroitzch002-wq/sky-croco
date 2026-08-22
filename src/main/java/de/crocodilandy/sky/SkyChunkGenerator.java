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
import net.minecraft.world.level.biome.Biome;
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

    /*
     * ============================================================
     * GRUNDEINSTELLUNGEN
     * ============================================================
     */

    private static final int SURFACE_Y = 100;

    private static final int MIN_SURFACE_Y = 88;
    private static final int MAX_SURFACE_Y = 118;

    private static final int MIN_BOTTOM_Y = 30;
    private static final int MAX_BOTTOM_Y = 60;

    private static final int WORLD_MIN_Y = -64;

    /*
     * Inselgruppen sind deutlich weiter voneinander entfernt.
     *
     * Eine Sichtweite von 32 Chunks entspricht ungefähr
     * 512 Blöcken. Mit diesen Zellen sollten andere Gruppen
     * meistens nur weit entfernt oder gar nicht sichtbar sein.
     */
    private static final int GROUP_CELL_SIZE = 900;

    /*
     * Nicht jede Zelle bekommt eine Gruppe.
     */
    private static final double GROUP_CHANCE = 0.72;

    /*
     * Hauptinseln pro Gruppe.
     */
    private static final int MIN_MAIN_ISLANDS = 2;
    private static final int MAX_MAIN_ISLANDS = 4;

    /*
     * Kleine und mittlere Nebeninseln.
     */
    private static final int MIN_SMALL_ISLANDS = 5;
    private static final int MAX_SMALL_ISLANDS = 10;

    /*
     * Größe der Hauptinseln.
     */
    private static final double MIN_MAIN_RADIUS = 28.0;
    private static final double MAX_MAIN_RADIUS = 58.0;

    /*
     * Kleine Inseln.
     */
    private static final double MIN_SMALL_RADIUS = 7.0;
    private static final double MAX_SMALL_RADIUS = 24.0;

    /*
     * Ausdehnung einer Inselgruppe.
     */
    private static final double GROUP_RADIUS = 190.0;

    private long worldSeed = 0L;

    public SkyChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /*
     * ============================================================
     * WELT-SEED
     * ============================================================
     */

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

    /*
     * ============================================================
     * CHUNK GENERIEREN
     * ============================================================
     */

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

                for (Island island : islands) {

                    int surface =
                            getSurfaceHeight(
                                    island,
                                    worldX,
                                    worldZ
                            );

                    if (surface == Integer.MIN_VALUE) {
                        continue;
                    }

                    int bottom =
                            getBottomHeight(
                                    island,
                                    worldX,
                                    worldZ,
                                    surface
                            );

                    for (int y = bottom; y <= surface; y++) {

                        if (!isInsideIsland(
                                island,
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
                                        island,
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
        }

        return CompletableFuture.completedFuture(chunk);
    }

    /*
     * ============================================================
     * INSELGRUPPEN
     * ============================================================
     */

    private List<Island> findNearbyIslands(
            int x,
            int z
    ) {
        List<Island> result =
                new ArrayList<>();

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
         * Nachbarzellen prüfen.
         */
        for (int offsetX = -1; offsetX <= 1; offsetX++) {

            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {

                int groupX =
                        cellX + offsetX;

                int groupZ =
                        cellZ + offsetZ;

                long cellSeed =
                        mixSeed(
                                worldSeed,
                                groupX,
                                groupZ
                        );

                RandomSource random =
                        RandomSource.create(cellSeed);

                if (random.nextDouble() > GROUP_CHANCE) {
                    continue;
                }

                /*
                 * Gruppenzentrum.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 240.0
                                - 120.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 240.0
                                - 120.0;

                /*
                 * =================================================
                 * BIOM DER GRUPPE
                 * =================================================
                 */

                IslandType type =
                        getIslandType(
                                centerX,
                                centerZ,
                                random
                        );

                /*
                 * Hauptinseln.
                 */
                int mainCount =
                        MIN_MAIN_ISLANDS
                                + random.nextInt(
                                MAX_MAIN_ISLANDS
                                        - MIN_MAIN_ISLANDS
                                        + 1
                        );

                for (int i = 0; i < mainCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double distance =
                            20.0
                                    + random.nextDouble()
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
                            MIN_MAIN_RADIUS
                                    + random.nextDouble()
                                    * (
                                    MAX_MAIN_RADIUS
                                            - MIN_MAIN_RADIUS
                            );

                    radius *=
                            type.sizeMultiplier;

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    false,
                                    type
                            )
                    );
                }

                /*
                 * Kleine und mittlere Nebeninseln.
                 */
                int smallCount =
                        MIN_SMALL_ISLANDS
                                + random.nextInt(
                                MAX_SMALL_ISLANDS
                                        - MIN_SMALL_ISLANDS
                                        + 1
                        );

                for (int i = 0; i < smallCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    /*
                     * Kleine Inseln dürfen auch etwas weiter
                     * außen liegen.
                     */
                    double distance =
                            25.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    0.7
                            )
                                    * (
                                    GROUP_RADIUS
                                            * 1.35
                            );

                    double islandX =
                            centerX
                                    + Math.cos(angle)
                                    * distance;

                    double islandZ =
                            centerZ
                                    + Math.sin(angle)
                                    * distance;

                    double radius =
                            MIN_SMALL_RADIUS
                                    + random.nextDouble()
                                    * (
                                    MAX_SMALL_RADIUS
                                            - MIN_SMALL_RADIUS
                            );

                    /*
                     * Manche werden zu winzigen Felsinseln.
                     */
                    if (random.nextDouble() < 0.30) {

                        radius *=
                                0.45
                                        + random.nextDouble()
                                        * 0.25;
                    }

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    true,
                                    type
                            )
                    );
                }
            }
        }

        return result;
    }

    /*
     * ============================================================
     * INSEL ERSTELLEN
     * ============================================================
     */

    private Island createIsland(
            RandomSource random,
            double x,
            double z,
            double radius,
            boolean small,
            IslandType type
    ) {
        int surfaceY =
                SURFACE_Y
                        + random.nextInt(
                        -type.heightVariation,
                        type.heightVariation + 1
                );

        int bottomY =
                MIN_BOTTOM_Y
                        + random.nextInt(
                        MAX_BOTTOM_Y
                                - MIN_BOTTOM_Y
                                + 1
                );

        /*
         * Kleine Inseln etwas kürzer.
         */
        if (small) {

            bottomY =
                    Math.max(
                            bottomY,
                            surfaceY
                                    - (
                                    22
                                            + random.nextInt(18)
                            )
                    );
        }

        double rotation =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        double stretchX =
                0.65
                        + random.nextDouble()
                        * 0.65;

        double stretchZ =
                0.65
                        + random.nextDouble()
                        * 0.65;

        double noiseOffset =
                random.nextDouble()
                        * 100000.0;

        double shapeOffset1 =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        double shapeOffset2 =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        double shapeOffset3 =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        boolean hasMountain =
                !small
                        && random.nextDouble()
                        < type.mountainChance;

        double mountainAngle =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        double mountainDistance =
                radius
                        * (
                        0.10
                                + random.nextDouble()
                                * 0.45
                );

        double mountainHeight =
                (
                        5.0
                                + random.nextDouble()
                                * 18.0
                )
                        * type.mountainMultiplier;

        return new Island(
                x,
                z,
                radius,
                surfaceY,
                bottomY,
                rotation,
                stretchX,
                stretchZ,
                noiseOffset,
                shapeOffset1,
                shapeOffset2,
                shapeOffset3,
                hasMountain,
                mountainAngle,
                mountainDistance,
                mountainHeight,
                type
        );
    }

    /*
     * ============================================================
     * BIOM-TYP BESTIMMEN
     * ============================================================
     *
     * Der aktuelle Generator verwendet die Weltkoordinaten,
     * um stabile regionale Varianten zu erzeugen.
     *
     * Dadurch entstehen große Gebiete mit unterschiedlichen
     * Inselarten.
     */

    private IslandType getIslandType(
            double x,
            double z,
            RandomSource random
    ) {
        long regionSeed =
                mixSeed(
                        worldSeed,
                        Math.floorDiv(
                                (long) x,
                                1200
                        ),
                        Math.floorDiv(
                                (long) z,
                                1200
                        )
                );

        RandomSource biomeRandom =
                RandomSource.create(
                        regionSeed
                );

        int value =
                biomeRandom.nextInt(9);

        return switch (value) {

            case 0 -> IslandType.PLAINS;

            case 1 -> IslandType.FOREST;

            case 2 -> IslandType.TAIGA;

            case 3 -> IslandType.DESERT;

            case 4 -> IslandType.JUNGLE;

            case 5 -> IslandType.SAVANNA;

            case 6 -> IslandType.SNOWY;

            case 7 -> IslandType.BADLANDS;

            default -> IslandType.ROCKY;
        };
    }

    /*
     * ============================================================
     * ORGANISCHE FORM
     * ============================================================
     */

    private double getOrganicRadius(
            Island island,
            double angle
    ) {
        double wave1 =
                Math.sin(
                        angle * 3.0
                                + island.shapeOffset1
                )
                        * 0.16;

        double wave2 =
                Math.sin(
                        angle * 5.0
                                + island.shapeOffset2
                )
                        * 0.11;

        double wave3 =
                Math.sin(
                        angle * 8.0
                                + island.shapeOffset3
                )
                        * 0.07;

        /*
         * Zusätzliche unregelmäßige Variation.
         */
        double variation =
                1.0
                        + wave1
                        + wave2
                        + wave3;

        return island.radius
                * variation;
    }

    /*
     * ============================================================
     * OBERFLÄCHE
     * ============================================================
     */

    private int getSurfaceHeight(
            Island island,
            int x,
            int z
    ) {
        double[] local =
                getLocalCoordinates(
                        island,
                        x,
                        z
                );

        double localX =
                local[0];

        double localZ =
                local[1];

        double distance =
                Math.sqrt(
                        localX * localX
                                + localZ * localZ
                );

        double angle =
                Math.atan2(
                        localZ,
                        localX
                );

        double organicRadius =
                getOrganicRadius(
                        island,
                        angle
                );

        if (distance > organicRadius) {
            return Integer.MIN_VALUE;
        }

        double edge =
                distance / organicRadius;

        /*
         * Große organische Oberflächenformen.
         */
        double broadNoise =
                terrainNoise(
                        x * 0.018,
                        z * 0.018,
                        island.noiseOffset
                )
                        * island.type.surfaceRoughness;

        /*
         * Kleine Variation.
         */
        double detailNoise =
                terrainNoise(
                        x * 0.055,
                        z * 0.055,
                        island.noiseOffset
                                + 381.2
                )
                        * 1.5
                        * Math.pow(
                        edge,
                        0.7
                );

        double mountain =
                getMountainHeight(
                        island,
                        localX,
                        localZ,
                        organicRadius
                );

        double center =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - edge
                        ),
                        2.0
                )
                        * 1.5;

        return clamp(
                (int) Math.round(
                        island.surfaceY
                                + broadNoise
                                + detailNoise
                                + center
                                + mountain
                ),
                MIN_SURFACE_Y,
                MAX_SURFACE_Y + 25
        );
    }

    /*
     * ============================================================
     * BERGE
     * ============================================================
     */

    private double getMountainHeight(
            Island island,
            double localX,
            double localZ,
            double organicRadius
    ) {
        if (!island.hasMountain) {
            return 0.0;
        }

        double mountainX =
                Math.cos(
                        island.mountainAngle
                )
                        * island.mountainDistance;

        double mountainZ =
                Math.sin(
                        island.mountainAngle
                )
                        * island.mountainDistance;

        double dx =
                localX
                        - mountainX;

        double dz =
                localZ
                        - mountainZ;

        double distance =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                );

        double mountainRadius =
                organicRadius
                        * 0.38;

        if (distance >= mountainRadius) {
            return 0.0;
        }

        double normalized =
                distance
                        / mountainRadius;

        double shape =
                Math.pow(
                        1.0
                                - normalized,
                        2.0
                );

        return shape
                * island.mountainHeight;
    }

    /*
     * ============================================================
     * UNTERSEITE
     * ============================================================
     */

    private int getBottomHeight(
            Island island,
            int x,
            int z,
            int surface
    ) {
        double[] local =
                getLocalCoordinates(
                        island,
                        x,
                        z
                );

        double distance =
                Math.sqrt(
                        local[0] * local[0]
                                + local[1] * local[1]
                );

        double angle =
                Math.atan2(
                        local[1],
                        local[0]
                );

        double radius =
                getOrganicRadius(
                        island,
                        angle
                );

        double edge =
                Math.min(
                        1.0,
                        distance / radius
                );

        /*
         * Außen endet die Insel schnell weiter oben.
         *
         * Dadurch bleibt die Unterseite spitz.
         */
        double taper =
                Math.pow(
                        edge,
                        1.25
                );

        int verticalRange =
                surface
                        - island.bottomY;

        int result =
                (int) Math.round(
                        island.bottomY
                                + taper
                                * verticalRange
                                * 0.76
                );

        double noise =
                terrainNoise(
                        x * 0.025,
                        z * 0.025,
                        island.noiseOffset
                                + 777.0
                )
                        * 2.0;

        result +=
                (int) Math.round(
                        noise
                );

        return Math.min(
                result,
                surface - 4
        );
    }

    /*
     * ============================================================
     * PRÜFEN OB BLOCK IN DER INSEL LIEGT
     * ============================================================
     */

    private boolean isInsideIsland(
            Island island,
            int x,
            int y,
            int z,
            int surface,
            int bottom
    ) {
        if (y > surface
                || y < bottom) {
            return false;
        }

        double[] local =
                getLocalCoordinates(
                        island,
                        x,
                        z
                );

        double distance =
                Math.sqrt(
                        local[0] * local[0]
                                + local[1] * local[1]
                );

        double angle =
                Math.atan2(
                        local[1],
                        local[0]
                );

        double organicRadius =
                getOrganicRadius(
                        island,
                        angle
                );

        double vertical =
                (double) (
                        y - bottom
                )
                        / Math.max(
                        1,
                        surface - bottom
                );

        /*
         * Sehr schmale Unterseite.
         */
        double widthFactor =
                0.04
                        + Math.pow(
                        vertical,
                        0.55
                )
                        * 0.96;

        double allowedRadius =
                organicRadius
                        * widthFactor;

        return distance <= allowedRadius;
    }

    /*
     * ============================================================
     * BLOCK JE NACH INSELTYP
     * ============================================================
     */

    private BlockState getBlockState(
            Island island,
            int x,
            int y,
            int z,
            int surface
    ) {
        IslandType type =
                island.type;

        /*
         * OBERFLÄCHE
         */
        if (y == surface) {

            return switch (type) {

                case DESERT ->
                        Blocks.SAND
                                .defaultBlockState();

                case BADLANDS ->
                        Blocks.RED_SAND
                                .defaultBlockState();

                case SNOWY ->
                        Blocks.SNOW_BLOCK
                                .defaultBlockState();

                case SAVANNA,
                     PLAINS,
                     FOREST,
                     JUNGLE,
                     TAIGA,
                     ROCKY ->
                        Blocks.GRASS_BLOCK
                                .defaultBlockState();
            };
        }

        /*
         * UNTERGRUND DIREKT UNTER DER OBERFLÄCHE
         */
        if (y >= surface - 3) {

            return switch (type) {

                case DESERT ->
                        Blocks.SANDSTONE
                                .defaultBlockState();

                case BADLANDS ->
                        Blocks.TERRACOTTA
                                .defaultBlockState();

                case SNOWY ->
                        Blocks.DIRT
                                .defaultBlockState();

                default ->
                        Blocks.DIRT
                                .defaultBlockState();
            };
        }

        /*
         * FELS
         */
        return switch (type) {

            case DESERT ->
                    Blocks.SANDSTONE
                            .defaultBlockState();

            case BADLANDS ->
                    Blocks.TERRACOTTA
                            .defaultBlockState();

            case SNOWY ->
                    Blocks.STONE
                            .defaultBlockState();

            case TAIGA,
                 ROCKY ->
                    Blocks.STONE
                            .defaultBlockState();

            default ->
                    Blocks.STONE
                            .defaultBlockState();
        };
    }

    /*
     * ============================================================
     * KOORDINATEN
     * ============================================================
     */

    private double[] getLocalCoordinates(
            Island island,
            int x,
            int z
    ) {
        double dx =
                x - island.x;

        double dz =
                z - island.z;

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

        localX /=
                island.stretchX;

        localZ /=
                island.stretchZ;

        return new double[]{
                localX,
                localZ
        };
    }

    /*
     * ============================================================
     * NOISE
     * ============================================================
     */

    private double terrainNoise(
            double x,
            double z,
            double offset
    ) {
        double a =
                Math.sin(
                        x + offset
                );

        double b =
                Math.sin(
                        z * 1.17
                                + offset * 1.31
                );

        double c =
                Math.sin(
                        (x + z)
                                * 0.67
                                + offset * 0.71
                );

        double d =
                Math.cos(
                        (x - z)
                                * 0.49
                                - offset * 1.09
                );

        return
                a * 0.30
                        + b * 0.25
                        + c * 0.25
                        + d * 0.20;
    }

    /*
     * ============================================================
     * SEED
     * ============================================================
     */

    private static long mixSeed(
            long worldSeed,
            long x,
            long z
    ) {
        long value =
                worldSeed
                        ^ (
                        x
                                * 0x9E3779B97F4A7C15L
                )
                        ^ (
                        z
                                * 0xC2B2AE3D27D4EB4FL
                );

        value ^=
                value >>> 30;

        value *=
                0xBF58476D1CE4E5B9L;

        value ^=
                value >>> 27;

        value *=
                0x94D049BB133111EBL;

        value ^=
                value >>> 31;

        return value;
    }

    /*
     * ============================================================
     * STRUKTUREN DEAKTIVIEREN
     * ============================================================
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
        // Keine Vanilla-Strukturen im Void.
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
        // Keine Höhlen.
    }

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        // Oberfläche wird bereits erzeugt.
    }

    @Override
    public void spawnOriginalMobs(
            WorldGenRegion worldGenRegion
    ) {
        // Vanilla-Mobs später.
    }

    /*
     * ============================================================
     * WELTHÖHE
     * ============================================================
     */

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

    /*
     * ============================================================
     * HEIGHTMAP
     * ============================================================
     */

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

        int highest =
                Integer.MIN_VALUE;

        for (Island island : islands) {

            int surface =
                    getSurfaceHeight(
                            island,
                            x,
                            z
                    );

            if (surface > highest) {
                highest = surface;
            }
        }

        if (highest == Integer.MIN_VALUE) {
            return WORLD_MIN_Y;
        }

        return highest + 1;
    }

    /*
     * ============================================================
     * BASE COLUMN
     * ============================================================
     */

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
                    Blocks.AIR
                            .defaultBlockState();
        }

        List<Island> islands =
                findNearbyIslands(
                        x,
                        z
                );

        int minY =
                heightAccessor.getMinY();

        for (Island island : islands) {

            int surface =
                    getSurfaceHeight(
                            island,
                            x,
                            z
                    );

            if (surface == Integer.MIN_VALUE) {
                continue;
            }

            int bottom =
                    getBottomHeight(
                            island,
                            x,
                            z,
                            surface
                    );

            for (int y = bottom; y <= surface; y++) {

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

                int index =
                        y - minY;

                if (index < 0
                        || index >= states.length) {
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
        }

        return new NoiseColumn(
                minY,
                states
        );
    }

    /*
     * ============================================================
     * DEBUG
     * ============================================================
     */

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
                "Island group spacing: ~900 blocks"
        );

        result.add(
                "Many islands per group"
        );

        result.add(
                "Biome island variants: enabled"
        );

        result.add(
                "Structures: disabled"
        );
    }

    /*
     * ============================================================
     * HILFSMETHODEN
     * ============================================================
     */

    private static int clamp(
            int value,
            int min,
            int max
    ) {
        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    /*
     * ============================================================
     * INSEL-BIOME / TYPEN
     * ============================================================
     */

    private enum IslandType {

        PLAINS(
                1.0,
                0.35,
                1.0,
                3.0,
                8
        ),

        FOREST(
                1.05,
                0.45,
                1.0,
                4.0,
                10
        ),

        TAIGA(
                1.0,
                0.65,
                1.25,
                5.5,
                13
        ),

        DESERT(
                0.95,
                0.25,
                0.75,
                2.0,
                7
        ),

        JUNGLE(
                1.20,
                0.60,
                1.15,
                5.0,
                12
        ),

        SAVANNA(
                1.05,
                0.55,
                1.30,
                4.5,
                11
        ),

        SNOWY(
                1.0,
                0.70,
                1.45,
                5.0,
                15
        ),

        BADLANDS(
                0.95,
                0.75,
                1.35,
                6.0,
                14
        ),

        ROCKY(
                0.90,
                0.80,
                1.55,
                6.0,
                16
        );

        final double sizeMultiplier;
        final double mountainChance;
        final double mountainMultiplier;
        final double surfaceRoughness;
        final int heightVariation;

        IslandType(
                double sizeMultiplier,
                double mountainChance,
                double mountainMultiplier,
                double surfaceRoughness,
                int heightVariation
        ) {
            this.sizeMultiplier =
                    sizeMultiplier;

            this.mountainChance =
                    mountainChance;

            this.mountainMultiplier =
                    mountainMultiplier;

            this.surfaceRoughness =
                    surfaceRoughness;

            this.heightVariation =
                    heightVariation;
        }
    }

    /*
     * ============================================================
     * INSELDATEN
     * ============================================================
     */

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
            double shapeOffset1,
            double shapeOffset2,
            double shapeOffset3,
            boolean hasMountain,
            double mountainAngle,
            double mountainDistance,
            double mountainHeight,
            IslandType type
    ) {
    }
}
