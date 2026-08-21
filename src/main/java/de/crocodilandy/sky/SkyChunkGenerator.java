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
     * =========================
     * Grundeinstellungen
     * =========================
     */

    private static final int SURFACE_Y = 100;

    private static final int MIN_SURFACE_Y = 90;
    private static final int MAX_SURFACE_Y = 115;

    /*
     * Die Unterseite der Inseln.
     * Der Körper läuft von der Oberfläche bis ungefähr hier.
     */
    private static final int MIN_BOTTOM_Y = 45;
    private static final int MAX_BOTTOM_Y = 65;

    private static final int WORLD_MIN_Y = -64;

    /*
     * =========================
     * Inselgruppen
     * =========================
     *
     * Die Gruppen liegen ungefähr 500–700 Blöcke
     * voneinander entfernt.
     */
    private static final int GROUP_CELL_SIZE = 600;

    /*
     * Jede Zelle bekommt eine Inselgruppe.
     *
     * Der zufällige Versatz verhindert ein sichtbares Raster.
     */
    private static final double GROUP_CHANCE = 1.0;

    /*
     * Anzahl der Inseln pro Gruppe.
     */
    private static final int MIN_ISLANDS_PER_GROUP = 3;
    private static final int MAX_ISLANDS_PER_GROUP = 7;

    /*
     * Größe einzelner Inseln.
     */
    private static final double MIN_ISLAND_RADIUS = 12.0;
    private static final double MAX_ISLAND_RADIUS = 48.0;

    /*
     * Inseln innerhalb einer Gruppe liegen näher
     * zusammen als vorher.
     */
    private static final double GROUP_RADIUS = 150.0;

    /*
     * Der echte Welt-Seed.
     */
    private long worldSeed = 0L;

    public SkyChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /*
     * =========================
     * Welt-Seed übernehmen
     * =========================
     */

    @Override
    public ChunkGeneratorStructureState createState(
            HolderLookup<
                    net.minecraft.world.level.levelgen.structure.StructureSet
                    > structureSetLookup,
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
     * =========================
     * Chunk generieren
     * =========================
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

        /*
         * Alle relevanten Inseln für diesen Chunk suchen.
         */
        List<Island> islands =
                findNearbyIslands(
                        worldStartX + 8,
                        worldStartZ + 8
                );

        for (int localX = 0; localX < 16; localX++) {

            int worldX =
                    worldStartX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {

                int worldZ =
                        worldStartZ + localZ;

                /*
                 * Höchste Inseloberfläche an dieser Position.
                 */
                int highestSurface =
                        Integer.MIN_VALUE;

                for (Island island : islands) {

                    int surface =
                            getSurfaceHeight(
                                    island,
                                    worldX,
                                    worldZ
                            );

                    if (surface > highestSurface) {
                        highestSurface = surface;
                    }
                }

                /*
                 * Hier existiert keine Insel.
                 */
                if (highestSurface == Integer.MIN_VALUE) {
                    continue;
                }

                /*
                 * Den kompletten Inselkörper von unten
                 * bis zur Oberfläche erzeugen.
                 */
                for (int y = MIN_BOTTOM_Y;
                     y <= highestSurface;
                     y++) {

                    Island bestIsland = null;
                    double bestDistance =
                            Double.MAX_VALUE;

                    for (Island island : islands) {

                        double distance =
                                horizontalDistance(
                                        island,
                                        worldX,
                                        worldZ
                                );

                        if (distance >
                                island.radius * 1.30) {
                            continue;
                        }

                        double allowedRadius =
                                getRadiusAtHeight(
                                        island,
                                        y,
                                        worldX,
                                        worldZ
                                );

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

                    BlockState state;

                    /*
                     * Grasoberfläche.
                     */
                    if (y >= surface - 1) {

                        state =
                                Blocks.GRASS_BLOCK.defaultBlockState();

                    /*
                     * Erdschicht.
                     */
                    } else if (y >= surface - 4) {

                        state =
                                Blocks.DIRT.defaultBlockState();

                    /*
                     * Felskörper.
                     */
                    } else {

                        state =
                                Blocks.STONE.defaultBlockState();
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

        return CompletableFuture.completedFuture(chunk);
    }

    /*
     * ============================================================
     * Inselgruppen finden
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
         * Benachbarte Gruppenzellen prüfen.
         */
        for (int offsetX = -1;
             offsetX <= 1;
             offsetX++) {

            for (int offsetZ = -1;
                 offsetZ <= 1;
                 offsetZ++) {

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
                 * Gruppe überspringen, falls deaktiviert.
                 */
                if (random.nextDouble()
                        > GROUP_CHANCE) {
                    continue;
                }

                /*
                 * Zentrum der Inselgruppe.
                 *
                 * Der Versatz sorgt dafür,
                 * dass kein sichtbares Raster entsteht.
                 */
                double centerX =
                        groupX
                                * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 180.0
                                - 90.0;

                double centerZ =
                        groupZ
                                * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 180.0
                                - 90.0;

                int islandCount =
                        MIN_ISLANDS_PER_GROUP
                                + random.nextInt(
                                MAX_ISLANDS_PER_GROUP
                                        - MIN_ISLANDS_PER_GROUP
                                        + 1
                        );

                /*
                 * Inseln erzeugen.
                 */
                for (int i = 0;
                     i < islandCount;
                     i++) {

                    double angle =
                            random.nextDouble()
                                    * Math.PI
                                    * 2.0;

                    /*
                     * Inseln liegen jetzt näher
                     * innerhalb ihrer Gruppe.
                     *
                     * Der Exponent 1.25 sorgt dafür,
                     * dass mehr Inseln näher am Zentrum liegen.
                     */
                    double distance =
                            18.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    1.25
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
                     * Unterschiedliche Größen.
                     */
                    double sizeVariation =
                            0.8
                                    + random.nextDouble()
                                    * 0.45;

                    radius *=
                            sizeVariation;

                    int surfaceY =
                            SURFACE_Y
                                    + random.nextInt(
                                    MIN_SURFACE_Y
                                            - SURFACE_Y,
                                    MAX_SURFACE_Y
                                            - SURFACE_Y
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

                    double stretchX =
                            0.82
                                    + random.nextDouble()
                                    * 0.38;

                    double stretchZ =
                            0.82
                                    + random.nextDouble()
                                    * 0.38;

                    double noiseOffset =
                            random.nextDouble()
                                    * 10000.0;

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
                                    noiseOffset
                            )
                    );
                }
            }
        }

        return result;
    }

    /*
     * ============================================================
     * Inseloberfläche
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

        if (distance > island.radius) {
            return Integer.MIN_VALUE;
        }

        double edge =
                distance
                        / island.radius;

        /*
         * Organische Oberfläche.
         */
        double roughness =
                terrainNoise(
                        x,
                        z,
                        island.noiseOffset
                );

        double edgeNoise =
                terrainNoise(
                        x * 2.0,
                        z * 2.0,
                        island.noiseOffset
                                + 193.7
                );

        double surfaceVariation =
                roughness * 3.5
                        + edgeNoise
                        * edge
                        * 4.5;

        /*
         * Leichte Erhöhung zur Mitte.
         */
        double broadShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - edge
                        ),
                        1.8
                ) * 2.2;

        int result =
                (int) Math.round(
                        island.surfaceY
                                + surfaceVariation
                                + broadShape
                );

        return MthClamp(
                result,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y
        );
    }

    /*
     * ============================================================
     * Inselkörper / Unterseite
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
            return 0.0;
        }

        /*
         * 0.0 = ganz unten
         * 1.0 = Oberfläche
         */
        double normalized =
                (double) (
                        y - island.bottomY
                )
                        / Math.max(
                        1,
                        surface - island.bottomY
                );

        /*
         * EXTREM SPITZ:
         *
         * Der hohe Exponent sorgt dafür,
         * dass die Insel unten sehr schnell
         * schmal wird.
         *
         * Dadurch entstehen deutlichere,
         * schwebende Felsformationen.
         */
        double verticalShape =
                0.025
                        + Math.pow(
                        normalized,
                        1.85
                ) * 0.975;

        /*
         * Organische Felsstruktur.
         *
         * Besonders die Unterseite bekommt
         * leichte natürliche Unregelmäßigkeiten.
         */
        double lowerNoise =
                terrainNoise(
                        x * 0.65,
                        z * 0.65,
                        island.noiseOffset
                                + y * 0.16
                );

        double noiseStrength =
                2.0
                        + (
                        1.0 - normalized
                ) * 3.5;

        double rockVariation =
                lowerNoise
                        * noiseStrength;

        /*
         * Kleine Mindestbreite,
         * damit keine kaputten Einzelblöcke
         * entstehen.
         */
        return Math.max(
                1.2,
                island.radius
                        * verticalShape
                        + rockVariation
        );
    }

    /*
     * ============================================================
     * Horizontaler Abstand
     * ============================================================
     */

    private double horizontalDistance(
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
     * ============================================================
     * Kontinuierliches Welt-Noise
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
                        (x + z) * 0.021
                                + offset * 0.73
                );

        double d =
                Math.cos(
                        (x - z) * 0.016
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
     * Seed mischen
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
     * ALLE Strukturen deaktivieren
     * ============================================================
     *
     * Dadurch sollen keine:
     *
     * - Strongholds
     * - Mineshafts
     * - Dörfer
     * - Trial Chambers
     * - Ancient Cities
     * - Bastions
     * - sonstigen Vanilla-Strukturen
     *
     * im Void generieren.
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
         */
    }

    /*
     * ============================================================
     * Keine Carver / Höhlen
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
         * Keine normalen Höhlen.
         */
    }

    /*
     * ============================================================
     * Oberfläche wird selbst generiert
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
         * Bereits in fillFromNoise erstellt.
         */
    }

    /*
     * ============================================================
     * Mobs später
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

    /*
     * ============================================================
     * Weltinformationen
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
     * Höhe bestimmen
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
     * Blocksäule bestimmen
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

        int highestSurface =
                Integer.MIN_VALUE;

        for (Island island : islands) {

            int surface =
                    getSurfaceHeight(
                            island,
                            x,
                            z
                    );

            if (surface > highestSurface) {
                highestSurface = surface;
            }
        }

        if (highestSurface == Integer.MIN_VALUE) {

            return new NoiseColumn(
                    heightAccessor.getMinY(),
                    states
            );
        }

        int minY =
                heightAccessor.getMinY();

        for (int y = MIN_BOTTOM_Y;
             y <= highestSurface;
             y++) {

            Island bestIsland = null;

            double bestDistance =
                    Double.MAX_VALUE;

            for (Island island : islands) {

                double distance =
                        horizontalDistance(
                                island,
                                x,
                                z
                        );

                if (distance >
                        island.radius * 1.30) {
                    continue;
                }

                double allowedRadius =
                        getRadiusAtHeight(
                                island,
                                y,
                                x,
                                z
                        );

                if (distance <= allowedRadius
                        && distance < bestDistance) {

                    bestDistance =
                            distance;

                    bestIsland =
                            island;
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
     * Debug-Informationen
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
                "Island groups: ~500-700 blocks apart"
        );

        result.add(
                "Islands: close groups, pointed undersides"
        );

        result.add(
                "Structures: disabled"
        );
    }

    /*
     * ============================================================
     * Hilfsmethoden
     * ============================================================
     */

    private static int MthClamp(
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
     * Insel-Daten
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
            double noiseOffset
    ) {
    }
}
