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
     * WELTEINSTELLUNGEN
     * ============================================================
     */

    private static final int SURFACE_Y = 100;

    private static final int MIN_SURFACE_Y = 92;
    private static final int MAX_SURFACE_Y = 112;

    private static final int MIN_BOTTOM_Y = 35;
    private static final int MAX_BOTTOM_Y = 58;

    private static final int WORLD_MIN_Y = -64;

    /*
     * Eine Gruppenzelle ist ungefähr 400 Blöcke groß.
     * Jede Zelle erzeugt eine Inselgruppe.
     */
    private static final int GROUP_CELL_SIZE = 400;

    /*
     * Sehr hohe Chance, damit beim Testen zuverlässig
     * Inselgruppen sichtbar sind.
     */
    private static final double GROUP_CHANCE = 0.88;

    /*
     * Anzahl Inseln pro Gruppe.
     */
    private static final int MIN_ISLANDS_PER_GROUP = 2;
    private static final int MAX_ISLANDS_PER_GROUP = 5;

    /*
     * Inselgrößen.
     *
     * Kleiner als zuvor, damit die Gruppen nicht aus
     * riesigen zusammenhängenden Landmassen bestehen.
     */
    private static final double MIN_ISLAND_RADIUS = 18.0;
    private static final double MAX_ISLAND_RADIUS = 42.0;

    /*
     * Radius der gesamten Inselgruppe.
     *
     * Die Inseln innerhalb der Gruppe liegen relativ nah,
     * aber Gruppen untereinander bleiben deutlich getrennt.
     */
    private static final double GROUP_RADIUS = 125.0;

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
     * SEED
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
     * CHUNK-GENERIERUNG
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

                        BlockState state;

                        /*
                         * Oberfläche.
                         *
                         * Nur direkt an der Oberfläche Gras,
                         * damit keine unschönen schwebenden
                         * Erdhaufen entstehen.
                         */
                        if (y == surface) {

                            state =
                                    Blocks.GRASS_BLOCK
                                            .defaultBlockState();

                        } else if (y >= surface - 3) {

                            state =
                                    Blocks.DIRT
                                            .defaultBlockState();

                        } else {

                            state =
                                    Blocks.STONE
                                            .defaultBlockState();
                        }

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
         * 5x5 prüfen.
         *
         * Dadurch werden auch Inselgruppen gefunden,
         * deren Rand in den aktuellen Bereich hineinragt.
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
                        RandomSource.create(
                                cellSeed
                        );

                /*
                 * Einige wenige Zellen bleiben leer.
                 */
                if (random.nextDouble() > GROUP_CHANCE) {
                    continue;
                }

                /*
                 * Gruppenzentrum.
                 *
                 * Der kleinere Zufallsversatz verhindert,
                 * dass Gruppen zu weit aus ihrer Zelle
                 * herauswandern.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 80.0
                                - 40.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 80.0
                                - 40.0;

                int islandCount =
                        MIN_ISLANDS_PER_GROUP
                                + random.nextInt(
                                MAX_ISLANDS_PER_GROUP
                                        - MIN_ISLANDS_PER_GROUP
                                        + 1
                        );

                for (int i = 0; i < islandCount; i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    /*
                     * Inseln innerhalb der Gruppe näher
                     * zusammen als die Gruppen untereinander.
                     */
                    double distance =
                            18.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    0.85
                            ) * GROUP_RADIUS;

                    double islandX =
                            centerX
                                    + Math.cos(angle)
                                    * distance;

                    double islandZ =
                            centerZ
                                    + Math.sin(angle)
                                    * distance;

                    double radius =
                            MIN_ISLAND_RADIUS
                                    + random.nextDouble()
                                    * (
                                    MAX_ISLAND_RADIUS
                                            - MIN_ISLAND_RADIUS
                            );

                    /*
                     * Einige kleine Nebeninseln.
                     */
                    if (random.nextDouble() < 0.28) {

                        radius *=
                                0.55
                                        + random.nextDouble()
                                        * 0.20;
                    }

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

                    double rotation =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    /*
                     * Nur leichte Streckung.
                     *
                     * Die eigentliche Unregelmäßigkeit kommt
                     * später über die organische Randfunktion.
                     */
                    double stretchX =
                            0.80
                                    + random.nextDouble()
                                    * 0.40;

                    double stretchZ =
                            0.80
                                    + random.nextDouble()
                                    * 0.40;

                    double noiseOffset =
                            random.nextDouble()
                                    * 100000.0;

                    /*
                     * Individuelle Parameter für organische Form.
                     */
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

                    /*
                     * Nur manche Inseln bekommen einen
                     * deutlicheren Berg.
                     */
                    boolean hasMountain =
                            random.nextDouble() < 0.38;

                    double mountainAngle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    double mountainDistance =
                            radius
                                    * (
                                    0.15
                                            + random.nextDouble()
                                            * 0.38
                            );

                    double mountainHeight =
                            4.0
                                    + random.nextDouble()
                                    * 12.0;

                    Island island =
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
                                    shapeOffset1,
                                    shapeOffset2,
                                    shapeOffset3,
                                    hasMountain,
                                    mountainAngle,
                                    mountainDistance,
                                    mountainHeight
                            );

                    result.add(island);
                }
            }
        }

        return result;
    }

    /*
     * ============================================================
     * ORGANISCHE INSELFORM
     * ============================================================
     */

    private double getOrganicRadius(
            Island island,
            double angle
    ) {
        /*
         * Mehrere unterschiedlich schnelle Wellen.
         *
         * Dadurch entstehen Ausbuchtungen, Buchten und
         * unregelmäßige Formen statt perfekter Kreise.
         */
        double wave1 =
                Math.sin(
                        angle * 3.0
                                + island.shapeOffset1
                ) * 0.11;

        double wave2 =
                Math.sin(
                        angle * 5.0
                                + island.shapeOffset2
                ) * 0.08;

        double wave3 =
                Math.sin(
                        angle * 7.0
                                + island.shapeOffset3
                ) * 0.05;

        double variation =
                1.0
                        + wave1
                        + wave2
                        + wave3;

        return island.radius * variation;
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
         * Breites, weiches Terrain.
         *
         * Diese Variation ist bewusst relativ klein,
         * damit keine zufälligen Erdsäulen entstehen.
         */
        double broadNoise =
                terrainNoise(
                        x * 0.020,
                        z * 0.020,
                        island.noiseOffset
                ) * 2.0;

        /*
         * Kleine Oberflächenvariation.
         */
        double detailNoise =
                terrainNoise(
                        x * 0.055,
                        z * 0.055,
                        island.noiseOffset + 291.7
                )
                        * 1.5
                        * Math.pow(edge, 0.8);

        /*
         * Inselzentrum bleibt größtenteils flach.
         */
        double centerShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - edge
                        ),
                        2.0
                ) * 1.8;

        double mountain =
                getMountainHeight(
                        island,
                        localX,
                        localZ,
                        organicRadius
                );

        int result =
                (int) Math.round(
                        island.surfaceY
                                + broadNoise
                                + detailNoise
                                + centerShape
                                + mountain
                );

        return clamp(
                result,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y + 12
        );
    }

    /*
     * ============================================================
     * EINZELNE BERGE
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
                localX - mountainX;

        double dz =
                localZ - mountainZ;

        double distance =
                Math.sqrt(
                        dx * dx
                                + dz * dz
                );

        /*
         * Bergbreite.
         */
        double mountainRadius =
                organicRadius * 0.32;

        if (distance >= mountainRadius) {
            return 0.0;
        }

        double normalized =
                distance / mountainRadius;

        /*
         * Sanfter Übergang statt einzelner Erdklumpen.
         */
        double shape =
                Math.pow(
                        1.0 - normalized,
                        2.2
                );

        return
                shape
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

        double edge =
                Math.min(
                        1.0,
                        distance / organicRadius
                );

        /*
         * Am Rand endet die Insel höher.
         * Dadurch wird die Unterseite nach unten
         * automatisch stark zugespitzt.
         */
        double taper =
                Math.pow(
                        edge,
                        1.35
                );

        int verticalRange =
                surface
                        - island.bottomY;

        int result =
                (int) Math.round(
                        island.bottomY
                                + taper
                                * verticalRange
                                * 0.70
                );

        /*
         * Unterseite leicht organisch,
         * aber nicht so stark, dass einzelne
         * Steinspitzen losgelöst aussehen.
         */
        double noise =
                terrainNoise(
                        x * 0.028,
                        z * 0.028,
                        island.noiseOffset + 777.0
                );

        result +=
                (int) Math.round(
                        noise * 2.0
                );

        return Math.min(
                result,
                surface - 4
        );
    }

    /*
     * ============================================================
     * INSELKÖRPER
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

        /*
         * Oben fast volle Breite.
         */
        double vertical =
                (double) (y - bottom)
                        / Math.max(
                        1,
                        surface - bottom
                );

        /*
         * Sehr schnelle Verbreiterung nach oben.
         *
         * Das sorgt für eine breite Insel oben
         * und eine deutlich spitze Unterseite.
         */
        double widthFactor =
                0.05
                        + Math.pow(
                        vertical,
                        0.55
                ) * 0.95;

        /*
         * Unterseite bekommt eine kleine
         * organische Variation.
         */
        double lowerVariation =
                terrainNoise(
                        x * 0.035,
                        z * 0.035,
                        island.noiseOffset
                                + y * 0.15
                )
                        * 1.5
                        * (1.0 - vertical);

        double allowedRadius =
                organicRadius
                        * widthFactor
                        + lowerVariation;

        return distance <= allowedRadius;
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
                        x
                                + offset
                );

        double b =
                Math.sin(
                        z * 1.13
                                + offset * 1.37
                );

        double c =
                Math.sin(
                        (x + z) * 0.71
                                + offset * 0.73
                );

        double d =
                Math.cos(
                        (x - z) * 0.53
                                - offset * 1.11
                );

        return
                a * 0.30
                        + b * 0.25
                        + c * 0.25
                        + d * 0.20;
    }

    /*
     * ============================================================
     * SEED-MISCHUNG
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
        /*
         * Absichtlich leer.
         *
         * Dadurch gibt es keine Strongholds,
         * Mineshafts oder Trial Chambers,
         * die im Void schweben könnten.
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
        // Keine Vanilla-Carver.
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
         * Die Oberfläche wird bereits in fillFromNoise gesetzt.
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
         * Wird später erweitert.
         */
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

            for (int y = bottom;
                 y <= surface;
                 y++) {

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

                if (y == surface) {

                    states[index] =
                            Blocks.GRASS_BLOCK
                                    .defaultBlockState();

                } else if (y >= surface - 3) {

                    states[index] =
                            Blocks.DIRT
                                    .defaultBlockState();

                } else {

                    states[index] =
                            Blocks.STONE
                                    .defaultBlockState();
                }
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
                "Island group spacing: ~400 blocks"
        );

        result.add(
                "Organic islands: enabled"
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
            double mountainHeight
    ) {
    }
}
