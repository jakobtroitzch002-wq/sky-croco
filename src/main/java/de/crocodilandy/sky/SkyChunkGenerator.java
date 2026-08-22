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

    /*
     * ============================================================
     * GRUNDEINSTELLUNGEN
     * ============================================================
     */

    private static final int SURFACE_Y = 100;

    private static final int MIN_SURFACE_Y = 92;
    private static final int MAX_SURFACE_Y = 112;

    /*
     * Die Inseln laufen nach unten sehr spitz aus.
     */
    private static final int MIN_BOTTOM_Y = 38;
    private static final int MAX_BOTTOM_Y = 62;

    private static final int WORLD_MIN_Y = -64;

    /*
     * ============================================================
     * INSELGRUPPEN
     * ============================================================
     *
     * Jede Zelle erzeugt IMMER eine Inselgruppe.
     *
     * Dadurch kann das Problem "gar keine Inseln mehr"
     * nicht mehr durch eine zu niedrige Chance entstehen.
     *
     * Die Gruppenzentren werden zufällig innerhalb ihrer
     * Zellen verschoben, damit kein sichtbares Raster entsteht.
     */

    private static final int GROUP_CELL_SIZE = 300;

    /*
     * Inseln innerhalb einer Gruppe.
     */
    private static final int MIN_ISLANDS_PER_GROUP = 3;
    private static final int MAX_ISLANDS_PER_GROUP = 6;

    /*
     * Kleinere Inseln als vorher.
     */
    private static final double MIN_ISLAND_RADIUS = 10.0;
    private static final double MAX_ISLAND_RADIUS = 32.0;

    /*
     * Wie weit Inseln vom Gruppenzentrum entfernt sein dürfen.
     *
     * Inseln innerhalb der Gruppe liegen näher zusammen.
     */
    private static final double GROUP_RADIUS = 80.0;

    /*
     * Tatsächlicher Welt-Seed.
     */
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

    /*
     * ============================================================
     * CHUNK GENERIERUNG
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

        List<Island> islands = findNearbyIslands(
                worldStartX + 8,
                worldStartZ + 8
        );

        for (int localX = 0; localX < 16; localX++) {

            int worldX = worldStartX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {

                int worldZ = worldStartZ + localZ;

                for (int y = MIN_BOTTOM_Y; y <= MAX_SURFACE_Y; y++) {

                    Island bestIsland = null;
                    double bestScore = Double.MAX_VALUE;

                    for (Island island : islands) {

                        if (!isInsideIsland(
                                island,
                                worldX,
                                y,
                                worldZ
                        )) {
                            continue;
                        }

                        double distance =
                                normalizedDistance(
                                        island,
                                        worldX,
                                        worldZ
                                );

                        if (distance < bestScore) {
                            bestScore = distance;
                            bestIsland = island;
                        }
                    }

                    if (bestIsland == null) {
                        continue;
                    }

                    int surface =
                            getSurfaceHeight(
                                    bestIsland,
                                    worldX,
                                    worldZ
                            );

                    BlockState state;

                    if (y >= surface - 1) {
                        state =
                                Blocks.GRASS_BLOCK.defaultBlockState();

                    } else if (y >= surface - 4) {
                        state =
                                Blocks.DIRT.defaultBlockState();

                    } else {
                        state =
                                Blocks.STONE.defaultBlockState();
                    }

                    chunk.setBlockState(
                            new BlockPos(worldX, y, worldZ),
                            state,
                            0
                    );
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
         * Wir prüfen 5x5 Zellen.
         *
         * Das ist absichtlich größer als vorher,
         * damit auch Inseln aus verschobenen Nachbarzellen
         * zuverlässig gefunden werden.
         */
        for (int offsetX = -2; offsetX <= 2; offsetX++) {

            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {

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

                /*
                 * Gruppenzentrum.
                 *
                 * Große zufällige Verschiebung,
                 * damit kein perfektes Raster sichtbar ist.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 100.0
                                - 50.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 100.0
                                - 50.0;

                int islandCount =
                        MIN_ISLANDS_PER_GROUP
                                + random.nextInt(
                                MAX_ISLANDS_PER_GROUP
                                        - MIN_ISLANDS_PER_GROUP
                                        + 1
                        );

                /*
                 * Die erste Insel liegt nahe dem Zentrum.
                 * Dadurch existiert in jeder Gruppe garantiert
                 * mindestens eine Insel.
                 */
                for (int i = 0; i < islandCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double distance;

                    if (i == 0) {

                        distance =
                                random.nextDouble() * 25.0;

                    } else {

                        /*
                         * Inseln einer Gruppe liegen
                         * relativ nahe beieinander.
                         */
                        distance =
                                20.0
                                        + Math.pow(
                                        random.nextDouble(),
                                        1.4
                                )
                                        * GROUP_RADIUS;
                    }

                    double islandX =
                            centerX
                                    + Math.cos(angle)
                                    * distance;

                    double islandZ =
                            centerZ
                                    + Math.sin(angle)
                                    * distance;

                    /*
                     * Kleinere Inseln.
                     */
                    double radius =
                            MIN_ISLAND_RADIUS
                                    + random.nextDouble()
                                    * (
                                    MAX_ISLAND_RADIUS
                                            - MIN_ISLAND_RADIUS
                            );

                    /*
                     * Einige Inseln etwas größer,
                     * aber keine riesigen Flächen mehr.
                     */
                    if (random.nextDouble() < 0.18) {
                        radius *= 1.25;
                    }

                    int surfaceY =
                            MIN_SURFACE_Y
                                    + random.nextInt(
                                    MAX_SURFACE_Y
                                            - MIN_SURFACE_Y
                                            + 1
                            );

                    /*
                     * Je nach Insel unterschiedlich lang.
                     */
                    int bottomY =
                            MIN_BOTTOM_Y
                                    + random.nextInt(
                                    MAX_BOTTOM_Y
                                            - MIN_BOTTOM_Y
                                            + 1
                            );

                    /*
                     * Manche Inseln werden deutlich länger
                     * oder ovaler als andere.
                     */
                    double stretchX =
                            0.65
                                    + random.nextDouble()
                                    * 0.75;

                    double stretchZ =
                            0.65
                                    + random.nextDouble()
                                    * 0.75;

                    double rotation =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double noiseOffset =
                            random.nextDouble()
                                    * 100000.0;

                    double mountainAngle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double mountainDistance =
                            random.nextDouble()
                                    * radius
                                    * 0.65;

                    double mountainStrength =
                            2.0
                                    + random.nextDouble()
                                    * 8.0;

                    result.add(
                            new Island(
                                    islandX,
                                    islandZ,
                                    radius,
                                    surfaceY,
                                    bottomY,
                                    rotation,
                                    stretchX,
                                    stretchZ,
                                    noiseOffset,
                                    mountainAngle,
                                    mountainDistance,
                                    mountainStrength
                            )
                    );
                }
            }
        }

        return result;
    }

    /*
     * ============================================================
     * PRÜFEN, OB EIN BLOCK IN DER INSEL LIEGT
     * ============================================================
     */

    private boolean isInsideIsland(
            Island island,
            int x,
            int y,
            int z
    ) {
        int surface =
                getSurfaceHeight(
                        island,
                        x,
                        z
                );

        if (surface == Integer.MIN_VALUE) {
            return false;
        }

        if (y > surface) {
            return false;
        }

        if (y < island.bottomY) {
            return false;
        }

        double normalizedY =
                (double) (
                        y - island.bottomY
                )
                        / Math.max(
                        1,
                        surface - island.bottomY
                );

        /*
         * Sehr starke Verjüngung nach unten.
         *
         * Dadurch werden die Inseln schnell spitz.
         */
        double verticalRadius =
                0.035
                        + Math.pow(
                        normalizedY,
                        1.65
                )
                        * 0.965;

        /*
         * Die Unterseite wird organisch verformt.
         */
        double noise =
                terrainNoise(
                        x * 0.55,
                        z * 0.55,
                        island.noiseOffset
                                + y * 0.17
                );

        double radiusNoise =
                noise
                        * 0.11
                        * (
                        1.0 - normalizedY
                );

        double allowed =
                verticalRadius
                        + radiusNoise;

        /*
         * Die minimale Spitze verhindert,
         * dass einzelne hässliche,
         * abgetrennte Blöcke entstehen.
         */
        if (allowed < 0.045) {
            allowed = 0.045;
        }

        double distance =
                normalizedDistance(
                        island,
                        x,
                        z
                );

        return distance <= allowed;
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
        double distance =
                normalizedDistance(
                        island,
                        x,
                        z
                );

        /*
         * Die organische Inselgrenze.
         */
        double edgeNoise =
                terrainNoise(
                        x * 0.75,
                        z * 0.75,
                        island.noiseOffset + 321.7
                );

        double boundary =
                1.0
                        + edgeNoise * 0.22;

        /*
         * Keine perfekten Kreise.
         */
        if (distance > boundary) {
            return Integer.MIN_VALUE;
        }

        double normalized =
                distance / boundary;

        /*
         * Grundrauschen.
         */
        double broadNoise =
                terrainNoise(
                        x * 0.045,
                        z * 0.045,
                        island.noiseOffset
                )
                        * 4.5;

        /*
         * Kleinere Details.
         */
        double detailNoise =
                terrainNoise(
                        x * 0.14,
                        z * 0.14,
                        island.noiseOffset + 800.0
                )
                        * 2.8;

        /*
         * An den Rändern stärkere Unebenheiten.
         */
        double edgeVariation =
                terrainNoise(
                        x * 0.09,
                        z * 0.09,
                        island.noiseOffset + 1700.0
                )
                        * normalized
                        * 5.0;

        /*
         * Vereinzelter Berg.
         *
         * Dadurch haben nicht alle Inseln nur eine
         * glatte flache Oberfläche.
         */
        double mountainX =
                island.x
                        + Math.cos(
                        island.mountainAngle
                )
                        * island.mountainDistance;

        double mountainZ =
                island.z
                        + Math.sin(
                        island.mountainAngle
                )
                        * island.mountainDistance;

        double dx =
                x - mountainX;

        double dz =
                z - mountainZ;

        double mountainRadius =
                Math.max(
                        8.0,
                        island.radius * 0.42
                );

        double mountainDistance =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                )
                        / mountainRadius;

        double mountain =
                Math.max(
                        0.0,
                        1.0
                                - mountainDistance
                );

        mountain =
                Math.pow(
                        mountain,
                        2.2
                )
                        * island.mountainStrength;

        /*
         * Die Mitte kann leicht höher sein,
         * aber nicht symmetrisch.
         */
        double centerShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - normalized
                        ),
                        2.0
                )
                        * 2.0;

        int result =
                (int) Math.round(
                        island.surfaceY
                                + broadNoise
                                + detailNoise
                                + edgeVariation
                                + mountain
                                + centerShape
                );

        return clamp(
                result,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y + 10
        );
    }

    /*
     * ============================================================
     * NORMALISIERTE DISTANZ
     * ============================================================
     *
     * 1.0 entspricht ungefähr dem Inselrand.
     */

    private double normalizedDistance(
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

        /*
         * Unterschiedliche Streckung.
         */
        double radiusX =
                island.radius
                        * island.stretchX;

        double radiusZ =
                island.radius
                        * island.stretchZ;

        double valueX =
                localX / radiusX;

        double valueZ =
                localZ / radiusZ;

        return Math.sqrt(
                valueX * valueX
                        + valueZ * valueZ
        );
    }

    /*
     * ============================================================
     * KONTINUIERLICHES TERRAIN NOISE
     * ============================================================
     */

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
                        (x + z)
                                * 0.021
                                + offset * 0.73
                );

        double d =
                Math.cos(
                        (x - z)
                                * 0.016
                                - offset * 1.11
                );

        double e =
                Math.sin(
                        x * 0.11
                                + z * 0.07
                                + offset * 0.31
                );

        return
                a * 0.24
                        + b * 0.21
                        + c * 0.20
                        + d * 0.18
                        + e * 0.17;
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
                        x * 0x9E3779B97F4A7C15L
                )
                        ^ (
                        z * 0xC2B2AE3D27D4EB4FL
                );

        value ^= value >>> 30;

        value *=
                0xBF58476D1CE4E5B9L;

        value ^= value >>> 27;

        value *=
                0x94D049BB133111EBL;

        value ^= value >>> 31;

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
        /*
         * Keine Strongholds.
         * Keine Mineshafts.
         * Keine Trial Chambers.
         * Keine anderen fliegenden Strukturen.
         */
    }

    /*
     * ============================================================
     * KEINE HÖHLEN
     * ============================================================
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
        /*
         * Keine Carver.
         */
    }

    /*
     * ============================================================
     * OBERFLÄCHE
     * ============================================================
     */

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        /*
         * Oberfläche wird bereits
         * in fillFromNoise erzeugt.
         */
    }

    /*
     * ============================================================
     * MOBS
     * ============================================================
     */

    @Override
    public void spawnOriginalMobs(
            WorldGenRegion worldGenRegion
    ) {
        /*
         * Vorerst deaktiviert.
         */
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
     * BLOCKSPALTE
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
                    Blocks.AIR.defaultBlockState();
        }

        List<Island> islands =
                findNearbyIslands(
                        x,
                        z
                );

        int minY =
                heightAccessor.getMinY();

        for (int y = MIN_BOTTOM_Y;
             y <= MAX_SURFACE_Y + 10;
             y++) {

            Island bestIsland = null;
            double bestScore = Double.MAX_VALUE;

            for (Island island : islands) {

                if (!isInsideIsland(
                        island,
                        x,
                        y,
                        z
                )) {
                    continue;
                }

                double distance =
                        normalizedDistance(
                                island,
                                x,
                                z
                        );

                if (distance < bestScore) {
                    bestScore = distance;
                    bestIsland = island;
                }
            }

            if (bestIsland == null) {
                continue;
            }

            int surface =
                    getSurfaceHeight(
                            bestIsland,
                            x,
                            z
                    );

            int index =
                    y - minY;

            if (index < 0
                    || index >= states.length) {
                continue;
            }

            if (y >= surface - 1) {

                states[index] =
                        Blocks.GRASS_BLOCK.defaultBlockState();

            } else if (y >= surface - 4) {

                states[index] =
                        Blocks.DIRT.defaultBlockState();

            } else {

                states[index] =
                        Blocks.STONE.defaultBlockState();
            }
        }

        return new NoiseColumn(
                minY,
                states
        );
    }

    /*
     * ============================================================
     * DEBUG INFO
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
                "Island groups: every ~600 blocks"
        );

        result.add(
                "Small organic islands"
        );

        result.add(
                "Structures: disabled"
        );
    }

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
     * INSEL DATEN
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
            double mountainAngle,
            double mountainDistance,
            double mountainStrength
    ) {
    }
}
