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
     * Grundeinstellungen
     * ============================================================
     */

    private static final int SURFACE_Y = 100;

    private static final int MIN_SURFACE_Y = 90;
    private static final int MAX_SURFACE_Y = 115;

    private static final int MIN_BOTTOM_Y = 45;
    private static final int MAX_BOTTOM_Y = 65;

    private static final int MIN_SPIKE_Y = 38;

    private static final int WORLD_MIN_Y = -64;

    /*
     * Große Zellen.
     *
     * Dadurch entstehen zwischen Inselgruppen große Void-Bereiche.
     * Der tatsächliche Abstand ist durch die zufällige Positionierung
     * unregelmäßig.
     */
    private static final int GROUP_CELL_SIZE = 1100;

    /*
     * Nicht jede Zelle enthält eine Inselgruppe.
     */
    private static final double GROUP_CHANCE = 0.1;

    /*
     * Unterschiedliche Gruppengrößen.
     */
    private static final int MIN_ISLANDS_PER_GROUP = 3;
    private static final int MAX_ISLANDS_PER_GROUP = 12;

    /*
     * Normale Inselgrößen.
     *
     * Kleinere Inseln sind deutlich häufiger als große.
     */
    private static final double MIN_ISLAND_RADIUS = 10.0;
    private static final double MAX_ISLAND_RADIUS = 50.0;

    /*
     * Inseln innerhalb einer Gruppe.
     */
    private static final double MIN_ISLAND_DISTANCE = 35.0;
    private static final double MAX_ISLAND_DISTANCE = 240.0;

    /*
     * Wie weit sich eine Gruppe ungefähr ausbreiten darf.
     */
    private static final double GROUP_RADIUS = 260.0;

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
     * Seed übernehmen
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
     * Haupt-Terrain-Generierung
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

                Island topIsland = null;
                int highestSurface = Integer.MIN_VALUE;

                /*
                 * Bestimme die oberste Insel an dieser Position.
                 */
                for (Island island : islands) {

                    int surface =
                            getSurfaceHeight(
                                    island,
                                    worldX,
                                    worldZ
                            );

                    if (surface > highestSurface) {
                        highestSurface = surface;
                        topIsland = island;
                    }
                }

                if (topIsland == null) {
                    continue;
                }

                /*
                 * Von der Inselunterseite bis zur Oberfläche.
                 */
                for (int y = MIN_SPIKE_Y; y <= highestSurface; y++) {

                    Island bestIsland = null;
                    double bestDistance = Double.MAX_VALUE;

                    for (Island island : islands) {

                        double distance =
                                getIslandDistance(
                                        island,
                                        worldX,
                                        worldZ
                                );

                        double allowedRadius =
                                getRadiusAtHeight(
                                        island,
                                        y,
                                        worldX,
                                        worldZ
                                );

                        if (allowedRadius <= 0.0) {
                            continue;
                        }

                        if (distance <= allowedRadius
                                && distance < bestDistance) {

                            bestDistance = distance;
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

                    if (surface == Integer.MIN_VALUE
                            || y > surface) {
                        continue;
                    }

                    BlockState state =
                            getBlockState(
                                    y,
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

    /*
     * ============================================================
     * Blockschichten
     * ============================================================
     */

    private BlockState getBlockState(
            int y,
            int surface
    ) {
        if (y == surface) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (y >= surface - 4) {
            return Blocks.DIRT.defaultBlockState();
        }

        return Blocks.STONE.defaultBlockState();
    }

    /*
     * ============================================================
     * Inselgruppen
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
         * Mehr Zellen prüfen, da Gruppen groß sein können.
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
                        RandomSource.create(
                                cellSeed
                        );

                if (random.nextDouble() > GROUP_CHANCE) {
                    continue;
                }

                /*
                 * Gruppenzentrum.
                 *
                 * Großer zufälliger Versatz verhindert
                 * sichtbare Raster.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 450.0
                                - 225.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 450.0
                                - 225.0;

                /*
                 * Seltene große Gruppen.
                 */
                boolean largeGroup =
                        random.nextDouble() < 0.15;

                int maxIslands =
                        largeGroup
                                ? 18
                                : MAX_ISLANDS_PER_GROUP;

                int islandCount =
                        MIN_ISLANDS_PER_GROUP
                                + random.nextInt(
                                maxIslands
                                        - MIN_ISLANDS_PER_GROUP
                                        + 1
                        );

                /*
                 * Manche Gruppen bekommen eine große Hauptinsel.
                 */
                boolean hasMainIsland =
                        random.nextDouble() < 0.65;

                if (hasMainIsland) {

                    double radius =
                            32.0
                                    + random.nextDouble()
                                    * 18.0;

                    result.add(
                            createIsland(
                                    random,
                                    centerX,
                                    centerZ,
                                    radius,
                                    true
                            )
                    );
                }

                /*
                 * Satelliten und normale Inseln.
                 */
                for (int i = 0; i < islandCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    /*
                     * Inseln sind innerhalb einer Gruppe
                     * nicht gleichmäßig verteilt.
                     */
                    double distance =
                            MIN_ISLAND_DISTANCE
                                    + Math.pow(
                                    random.nextDouble(),
                                    1.35
                            )
                                    * (MAX_ISLAND_DISTANCE
                                    - MIN_ISLAND_DISTANCE);

                    /*
                     * Einige Inseln liegen weiter außen.
                     */
                    if (random.nextDouble() < 0.20) {
                        distance +=
                                random.nextDouble()
                                        * 80.0;
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
                     * Kleine Inseln sind häufiger.
                     */
                    double sizeFactor =
                            Math.pow(
                                    random.nextDouble(),
                                    1.7
                            );

                    double radius =
                            MIN_ISLAND_RADIUS
                                    + sizeFactor
                                    * (MAX_ISLAND_RADIUS
                                    - MIN_ISLAND_RADIUS);

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    false
                            )
                    );
                }
            }
        }

        return result;
    }

    /*
     * ============================================================
     * Insel erzeugen
     * ============================================================
     */

    private Island createIsland(
            RandomSource random,
            double x,
            double z,
            double radius,
            boolean mainIsland
    ) {
        int surfaceY =
                MIN_SURFACE_Y
                        + random.nextInt(
                        MAX_SURFACE_Y
                                - MIN_SURFACE_Y
                                + 1
                );

        int bottomY =
                MIN_BOTTOM_Y
                        + random.nextInt(
                        MAX_BOTTOM_Y
                                - MIN_BOTTOM_Y
                                + 1
                );

        /*
         * Große Inseln können etwas tiefer hängen.
         */
        if (mainIsland
                && random.nextDouble() < 0.40) {

            bottomY -=
                    random.nextInt(0, 10);
        }

        double rotation =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        /*
         * Unterschiedliche Grundstreckung.
         */
        double stretchX =
                0.70
                        + random.nextDouble()
                        * 0.75;

        double stretchZ =
                0.70
                        + random.nextDouble()
                        * 0.75;

        double noiseOffset =
                random.nextDouble()
                        * 100000.0;

        /*
         * Asymmetrische Ausbuchtungen.
         */
        int featureCount =
                3 + random.nextInt(5);

        List<ShapeFeature> features =
                new ArrayList<>();

        for (int i = 0;
             i < featureCount;
             i++) {

            double angle =
                    random.nextDouble()
                            * Math.PI
                            * 2.0;

            double strength =
                    radius
                            * (
                            0.12
                                    + random.nextDouble()
                                    * 0.35
                    );

            double width =
                    0.35
                            + random.nextDouble()
                            * 0.65;

            features.add(
                    new ShapeFeature(
                            angle,
                            strength,
                            width
                    )
            );
        }

        /*
         * Hügel und kleine Berge auf der Oberfläche.
         */
        int hillCount =
                mainIsland
                        ? 3 + random.nextInt(5)
                        : 1 + random.nextInt(4);

        List<Hill> hills =
                new ArrayList<>();

        for (int i = 0;
             i < hillCount;
             i++) {

            double angle =
                    random.nextDouble()
                            * Math.PI
                            * 2.0;

            double distance =
                    random.nextDouble()
                            * radius
                            * 0.65;

            double hillX =
                    Math.cos(angle)
                            * distance;

            double hillZ =
                    Math.sin(angle)
                            * distance;

            double hillRadius =
                    radius
                            * (
                            0.15
                                    + random.nextDouble()
                                    * 0.28
                    );

            double hillHeight =
                    1.5
                            + random.nextDouble()
                            * (
                            mainIsland
                                    ? 8.0
                                    : 5.0
                    );

            hills.add(
                    new Hill(
                            hillX,
                            hillZ,
                            hillRadius,
                            hillHeight
                    )
            );
        }

        return new Island(
                x,
                z,
                radius,
                surfaceY,
                Math.max(
                        MIN_SPIKE_Y,
                        bottomY
                ),
                rotation,
                stretchX,
                stretchZ,
                noiseOffset,
                features,
                hills
        );
    }

    /*
     * ============================================================
     * Organische Inselgrenze
     * ============================================================
     */

    private double getIslandDistance(
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

        return Math.sqrt(
                localX * localX
                        + localZ * localZ
        );
    }

    /*
     * Der effektive Radius hängt von der Richtung ab.
     *
     * Dadurch entsteht keine kreisförmige Insel.
     */
    private double getEffectiveRadius(
            Island island,
            int x,
            int z
    ) {
        double dx =
                x - island.x;

        double dz =
                z - island.z;

        double angle =
                Math.atan2(
                        dz,
                        dx
                );

        double radius =
                island.radius;

        /*
         * Mehrere große organische Noise-Ebenen.
         */
        double broadNoise =
                terrainNoise(
                        x * 0.45,
                        z * 0.45,
                        island.noiseOffset
                );

        double mediumNoise =
                terrainNoise(
                        x * 1.15,
                        z * 1.15,
                        island.noiseOffset + 713.0
                );

        double fineNoise =
                terrainNoise(
                        x * 2.6,
                        z * 2.6,
                        island.noiseOffset + 1937.0
                );

        radius +=
                broadNoise
                        * island.radius
                        * 0.22;

        radius +=
                mediumNoise
                        * island.radius
                        * 0.12;

        radius +=
                fineNoise
                        * island.radius
                        * 0.05;

        /*
         * Einzelne starke Ausbuchtungen.
         *
         * Diese machen die Insel deutlich asymmetrischer.
         */
        for (ShapeFeature feature
                : island.features) {

            double difference =
                    angleDifference(
                            angle,
                            feature.angle
                    );

            double influence =
                    Math.exp(
                            -(
                                    difference
                                            * difference
                            )
                                    / (
                                    2.0
                                            * feature.width
                                            * feature.width
                            )
                    );

            radius +=
                    feature.strength
                            * influence;
        }

        return Math.max(
                island.radius * 0.45,
                radius
        );
    }

    /*
     * ============================================================
     * Oberfläche
     * ============================================================
     */

    private int getSurfaceHeight(
            Island island,
            int x,
            int z
    ) {
        double distance =
                getIslandDistance(
                        island,
                        x,
                        z
                );

        double effectiveRadius =
                getEffectiveRadius(
                        island,
                        x,
                        z
                );

        if (distance > effectiveRadius) {
            return Integer.MIN_VALUE;
        }

        double normalized =
                distance
                        / effectiveRadius;

        /*
         * Grundrauschen.
         */
        double broadNoise =
                terrainNoise(
                        x * 0.30,
                        z * 0.30,
                        island.noiseOffset + 100.0
                );

        double mediumNoise =
                terrainNoise(
                        x * 0.85,
                        z * 0.85,
                        island.noiseOffset + 500.0
                );

        double fineNoise =
                terrainNoise(
                        x * 2.0,
                        z * 2.0,
                        island.noiseOffset + 900.0
                );

        double variation =
                broadNoise * 4.0
                        + mediumNoise * 2.8
                        + fineNoise * 1.2;

        /*
         * Hügel.
         */
        double hills =
                getHillHeight(
                        island,
                        x,
                        z
                );

        /*
         * Leichte zentrale Erhebung.
         *
         * Nicht stark genug für eine perfekte Kuppel.
         */
        double centerLift =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - normalized
                        ),
                        2.2
                )
                        * 2.5;

        /*
         * Ränder werden etwas unruhiger.
         */
        double edgeVariation =
                terrainNoise(
                        x * 1.5,
                        z * 1.5,
                        island.noiseOffset + 3000.0
                )
                        * normalized
                        * 3.5;

        int result =
                (int) Math.round(
                        island.surfaceY
                                + variation
                                + hills
                                + centerLift
                                + edgeVariation
                );

        return clamp(
                result,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y
        );
    }

    /*
     * ============================================================
     * Hügel und Berge
     * ============================================================
     */

    private double getHillHeight(
            Island island,
            int x,
            int z
    ) {
        double total = 0.0;

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

        for (Hill hill
                : island.hills) {

            double hillDX =
                    localX - hill.x;

            double hillDZ =
                    localZ - hill.z;

            double distance =
                    Math.sqrt(
                            hillDX * hillDX
                                    + hillDZ * hillDZ
                    );

            if (distance > hill.radius) {
                continue;
            }

            double normalized =
                    distance
                            / hill.radius;

            double shape =
                    1.0
                            - normalized
                            * normalized;

            total +=
                    hill.height
                            * shape;
        }

        return total;
    }

    /*
     * ============================================================
     * Insel-Unterseite
     * ============================================================
     */

    private double getRadiusAtHeight(
            Island island,
            int y,
            int x,
            int z
    ) {
        int surface =
                getSurfaceHeight(
                        island,
                        x,
                        z
                );

        if (surface == Integer.MIN_VALUE) {
            return 0.0;
        }

        if (y > surface) {
            return 0.0;
        }

        if (y < island.bottomY) {

            /*
             * Einzelne Spitzen unterhalb der normalen Insel.
             *
             * Sie bleiben mit dem Inselkörper verbunden.
             */
            double spike =
                    getSpikeDepth(
                            island,
                            x,
                            z
                    );

            if (spike <= 0.0
                    || y < island.bottomY - spike) {

                return 0.0;
            }
        }

        double effectiveRadius =
                getEffectiveRadius(
                        island,
                        x,
                        z
                );

        double normalizedHeight =
                (double) (
                        y - island.bottomY
                )
                        / Math.max(
                        1,
                        surface
                                - island.bottomY
                );

        normalizedHeight =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                normalizedHeight
                        )
                );

        /*
         * Stark verjüngte Unterseite.
         *
         * Oben fast volle Breite,
         * unten sehr schmal.
         */
        double verticalShape =
                0.05
                        + Math.pow(
                        normalizedHeight,
                        1.65
                )
                        * 0.95;

        /*
         * Unterseite wird organisch,
         * bleibt aber zusammenhängend.
         */
        double undersideNoise =
                terrainNoise(
                        x * 0.8,
                        z * 0.8,
                        island.noiseOffset
                                + y * 7.0
                )
                        * (1.0 - normalizedHeight)
                        * effectiveRadius
                        * 0.08;

        double radius =
                effectiveRadius
                        * verticalShape
                        + undersideNoise;

        /*
         * Spitzen werden nach unten zusätzlich schmaler.
         */
        if (y < island.bottomY) {

            double spikeDepth =
                    island.bottomY
                            - y;

            radius -=
                    spikeDepth
                            * 0.9;
        }

        return Math.max(
                0.0,
                radius
        );
    }

    /*
     * ============================================================
     * Zusammenhängende natürliche Spitzen
     * ============================================================
     */

    private double getSpikeDepth(
            Island island,
            int x,
            int z
    ) {
        double distance =
                getIslandDistance(
                        island,
                        x,
                        z
                );

        double radius =
                getEffectiveRadius(
                        island,
                        x,
                        z
                );

        if (distance > radius * 0.65) {
            return 0.0;
        }

        double spikeNoise =
                terrainNoise(
                        x * 0.35,
                        z * 0.35,
                        island.noiseOffset + 8000.0
                );

        /*
         * Nur einige Bereiche bekommen längere Spitzen.
         */
        if (spikeNoise < 0.35) {
            return 0.0;
        }

        double centerFactor =
                1.0
                        - distance
                        / (radius * 0.65);

        return
                Math.max(
                        0.0,
                        spikeNoise
                                * 18.0
                                * centerFactor
                );
    }

    /*
     * ============================================================
     * Kontinuierliches Noise
     * ============================================================
     */

    private double terrainNoise(
            double x,
            double z,
            double offset
    ) {
        double a =
                Math.sin(
                        x * 0.031
                                + offset
                );

        double b =
                Math.sin(
                        z * 0.043
                                + offset * 1.37
                );

        double c =
                Math.sin(
                        (x + z)
                                * 0.019
                                + offset * 0.73
                );

        double d =
                Math.cos(
                        (x - z)
                                * 0.013
                                - offset * 1.11
                );

        double e =
                Math.sin(
                        x * 0.071
                                + z * 0.053
                                + offset * 0.41
                );

        return
                a * 0.24
                        + b * 0.20
                        + c * 0.20
                        + d * 0.18
                        + e * 0.18;
    }

    private double angleDifference(
            double a,
            double b
    ) {
        double difference =
                a - b;

        while (difference > Math.PI) {
            difference -=
                    Math.PI * 2.0;
        }

        while (difference < -Math.PI) {
            difference +=
                    Math.PI * 2.0;
        }

        return difference;
    }

    /*
     * ============================================================
     * Seed-Mischung
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
     * Strukturen
     * ============================================================
     *
     * Vorerst deaktiviert, damit keine Trial Chambers,
     * Strongholds oder Mineshafts im Void schweben.
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
        // Absichtlich leer.
    }

    /*
     * ============================================================
     * Keine Carver
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
        // Keine Höhlen.
    }

    /*
     * ============================================================
     * Oberfläche
     * ============================================================
     */

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        // Oberfläche wird bereits in fillFromNoise gesetzt.
    }

    /*
     * ============================================================
     * Mobs
     * ============================================================
     */

    @Override
    public void spawnOriginalMobs(
            WorldGenRegion worldGenRegion
    ) {
        // Später können hier Vanilla-Mobs aktiviert werden.
    }

    /*
     * ============================================================
     * Welt-Einstellungen
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
     * Base Height
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
     * Base Column
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

        for (int i = 0;
             i < states.length;
             i++) {

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

        int maxY =
                heightAccessor.getMaxY() - 1;

        for (int y = MIN_SPIKE_Y;
             y <= maxY;
             y++) {

            Island bestIsland = null;
            double bestDistance = Double.MAX_VALUE;

            for (Island island : islands) {

                double distance =
                        getIslandDistance(
                                island,
                                x,
                                z
                        );

                double allowedRadius =
                        getRadiusAtHeight(
                                island,
                                y,
                                x,
                                z
                        );

                if (allowedRadius <= 0.0) {
                    continue;
                }

                if (distance <= allowedRadius
                        && distance < bestDistance) {

                    bestDistance = distance;
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

            if (surface == Integer.MIN_VALUE
                    || y > surface) {

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
                            y,
                            surface
                    );
        }

        return new NoiseColumn(
                minY,
                states
        );
    }

    /*
     * ============================================================
     * Debug-Information
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
                "Organic asymmetric islands"
        );

        result.add(
                "Large void between groups"
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
     * Datenklassen
     * ============================================================
     */

    private record ShapeFeature(
            double angle,
            double strength,
            double width
    ) {
    }

    private record Hill(
            double x,
            double z,
            double radius,
            double height
    ) {
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
            List<ShapeFeature> features,
            List<Hill> hills
    ) {
    }
}
