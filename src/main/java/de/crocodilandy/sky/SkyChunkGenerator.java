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
     * WELT / HÖHE
     * ============================================================
     */

    private static final int SURFACE_Y = 100;
    private static final int MIN_SURFACE_Y = 88;
    private static final int MAX_SURFACE_Y = 120;

    private static final int MIN_BOTTOM_Y = 25;
    private static final int MAX_BOTTOM_Y = 60;

    private static final int WORLD_MIN_Y = -64;

    /*
     * ============================================================
     * INSELGRUPPEN
     * ============================================================
     *
     * Eine Zelle ist relativ groß. Dadurch sind Gruppen deutlich
     * weiter auseinander. Mit 32 Chunks Sichtweite sollte man
     * normalerweise nicht überall Inselgruppen sehen.
     */

    private static final int GROUP_CELL_SIZE = 850;

    /*
     * Fast jede Zelle enthält eine Gruppe, aber durch den großen
     * Abstand bleiben zwischen den Gruppen große Void-Bereiche.
     */
    private static final double GROUP_CHANCE = 0.78;

    /*
     * Hauptinseln pro Gruppe.
     */
    private static final int MIN_MAIN_ISLANDS = 3;
    private static final int MAX_MAIN_ISLANDS = 7;

    /*
     * Kleine Nebeninseln.
     */
    private static final int MIN_SMALL_ISLANDS = 4;
    private static final int MAX_SMALL_ISLANDS = 10;

    /*
     * Radius der Hauptinseln.
     */
    private static final double MIN_MAIN_RADIUS = 22.0;
    private static final double MAX_MAIN_RADIUS = 52.0;

    /*
     * Radius kleiner Inseln.
     */
    private static final double MIN_SMALL_RADIUS = 6.0;
    private static final double MAX_SMALL_RADIUS = 20.0;

    /*
     * Inseln innerhalb einer Gruppe liegen näher zusammen.
     */
    private static final double MAIN_GROUP_RADIUS = 210.0;
    private static final double SMALL_GROUP_RADIUS = 280.0;

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
     * CHUNK GENERATION
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

                    for (int y = island.bottomY; y <= surface; y++) {

                        if (!isInsideIsland(
                                island,
                                worldX,
                                y,
                                worldZ
                        )) {
                            continue;
                        }

                        BlockState state =
                                getBlockForIsland(
                                        island,
                                        y,
                                        surface
                                );

                        chunk.setBlockState(
                                new BlockPos(worldX, y, worldZ),
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

        int cellX = Math.floorDiv(x, GROUP_CELL_SIZE);
        int cellZ = Math.floorDiv(z, GROUP_CELL_SIZE);

        /*
         * 3x3 reicht wegen der Gruppengröße.
         */
        for (int offsetX = -1; offsetX <= 1; offsetX++) {

            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {

                int groupX = cellX + offsetX;
                int groupZ = cellZ + offsetZ;

                long seed =
                        mixSeed(
                                worldSeed,
                                groupX,
                                groupZ
                        );

                RandomSource random =
                        RandomSource.create(seed);

                if (random.nextDouble() > GROUP_CHANCE) {
                    continue;
                }

                /*
                 * Gruppenzentrum.
                 *
                 * Großer zufälliger Versatz verhindert ein Raster.
                 */
                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 300.0
                                - 150.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 300.0
                                - 150.0;

                /*
                 * Der Gruppentyp bestimmt Form und Material.
                 */
                IslandType type =
                        IslandType.values()[
                                random.nextInt(
                                        IslandType.values().length
                                )
                        ];

                /*
                 * =================================================
                 * HAUPTINSELN
                 * =================================================
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

                    /*
                     * Nicht alle Inseln gleich weit vom Zentrum.
                     */
                    double distance =
                            25.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    1.45
                            ) * MAIN_GROUP_RADIUS;

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

                    /*
                     * Manche Inseln sind deutlich größer.
                     */
                    if (random.nextDouble() < 0.18) {
                        radius *=
                                1.15
                                        + random.nextDouble() * 0.35;
                    }

                    int surfaceY =
                            SURFACE_Y
                                    + random.nextInt(
                                    MIN_SURFACE_Y - SURFACE_Y,
                                    MAX_SURFACE_Y - SURFACE_Y + 1
                            );

                    int bottomY =
                            MIN_BOTTOM_Y
                                    + random.nextInt(
                                    MAX_BOTTOM_Y
                                            - MIN_BOTTOM_Y
                                            + 1
                            );

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    surfaceY,
                                    bottomY,
                                    type
                            )
                    );
                }

                /*
                 * =================================================
                 * KLEINE NEBENINSELN
                 * =================================================
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

                    double distance =
                            60.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    1.2
                            ) * SMALL_GROUP_RADIUS;

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

                    int surfaceY =
                            SURFACE_Y
                                    + random.nextInt(
                                    -8,
                                    9
                            );

                    int bottomY =
                            50
                                    + random.nextInt(
                                    12
                            );

                    result.add(
                            createIsland(
                                    random,
                                    islandX,
                                    islandZ,
                                    radius,
                                    surfaceY,
                                    bottomY,
                                    type
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
            IslandType type
    ) {
        double rotation =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        /*
         * Unterschiedliche Streckung verhindert perfekte Kreise.
         */
        double stretchX =
                0.70
                        + random.nextDouble() * 0.65;

        double stretchZ =
                0.70
                        + random.nextDouble() * 0.65;

        /*
         * Mehrere Noise-Werte sorgen für individuelle Inseln.
         */
        double noiseOffset =
                random.nextDouble() * 100000.0;

        double mountainStrength =
                0.4
                        + random.nextDouble() * 1.4;

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
                mountainStrength,
                type
        );
    }

    /*
     * ============================================================
     * INSEL-FORM
     * ============================================================
     */

    private int getSurfaceHeight(
            Island island,
            int x,
            int z
    ) {
        double distance =
                getNormalizedDistance(
                        island,
                        x,
                        z
                );

        /*
         * Organischer Rand.
         */
        double edgeNoise =
                terrainNoise(
                        x * 0.055,
                        z * 0.055,
                        island.noiseOffset
                );

        double effectiveRadius =
                island.radius
                        * (
                        1.0
                                + edgeNoise * 0.18
                );

        if (distance > effectiveRadius) {
            return Integer.MIN_VALUE;
        }

        double normalized =
                distance / effectiveRadius;

        /*
         * Große Berge / Unebenheiten.
         *
         * Sie werden hauptsächlich im Inneren der Insel erzeugt,
         * damit keine einzelnen Erdhaufen am Rand entstehen.
         */
        double mountainNoise =
                terrainNoise(
                        x * 0.018,
                        z * 0.018,
                        island.noiseOffset + 500.0
                );

        double detailNoise =
                terrainNoise(
                        x * 0.075,
                        z * 0.075,
                        island.noiseOffset + 1700.0
                );

        /*
         * Die Unebenheiten werden zum Rand hin abgeschwächt.
         */
        double interior =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - normalized
                        ),
                        0.55
                );

        /*
         * Leichte natürliche Grundform.
         */
        double baseShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - normalized
                        ),
                        1.6
                ) * 2.5;

        /*
         * Größere einzelne Berge.
         */
        double mountains =
                Math.max(
                        0.0,
                        mountainNoise
                )
                        * 15.0
                        * island.mountainStrength
                        * interior;

        /*
         * Kleine Unebenheiten.
         */
        double detail =
                detailNoise
                        * 3.0
                        * interior;

        int height =
                (int) Math.round(
                        island.surfaceY
                                + baseShape
                                + mountains
                                + detail
                );

        return clamp(
                height,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y
        );
    }

    /*
     * ============================================================
     * INSELKÖRPER
     * ============================================================
     *
     * Hier wird die sehr spitze Unterseite erzeugt.
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

        if (y > surface || y < island.bottomY) {
            return false;
        }

        double distance =
                getNormalizedDistance(
                        island,
                        x,
                        z
                );

        double normalizedHeight =
                (double) (y - island.bottomY)
                        / Math.max(
                        1,
                        surface - island.bottomY
                );

        /*
         * Sehr schnelle Verjüngung nach unten.
         *
         * Dadurch wird die Insel deutlich spitzer.
         */
        double shape =
                0.035
                        + Math.pow(
                        normalizedHeight,
                        0.48
                ) * 0.965;

        /*
         * Kleine natürliche Felsverformung.
         */
        double rockNoise =
                terrainNoise(
                        x * 0.06,
                        z * 0.06,
                        island.noiseOffset
                                + y * 0.17
                );

        double allowedRadius =
                island.radius
                        * shape
                        * (
                        1.0
                                + rockNoise * 0.08
                );

        return distance <= allowedRadius;
    }

    /*
     * ============================================================
     * BLOCKS NACH INSELTYP
     * ============================================================
     */

    private BlockState getBlockForIsland(
            Island island,
            int y,
            int surface
    ) {
        int depth =
                surface - y;

        if (depth <= 1) {

            return switch (island.type) {

                case DESERT ->
                        Blocks.SAND.defaultBlockState();

                case BADLANDS ->
                        Blocks.RED_SAND.defaultBlockState();

                case SNOW ->
                        Blocks.SNOW_BLOCK.defaultBlockState();

                case STONY ->
                        Blocks.STONE.defaultBlockState();

                default ->
                        Blocks.GRASS_BLOCK.defaultBlockState();
            };
        }

        if (depth <= 4) {

            return switch (island.type) {

                case DESERT ->
                        Blocks.SAND.defaultBlockState();

                case BADLANDS ->
                        Blocks.TERRACOTTA.defaultBlockState();

                case SNOW ->
                        Blocks.DIRT.defaultBlockState();

                case STONY ->
                        Blocks.STONE.defaultBlockState();

                default ->
                        Blocks.DIRT.defaultBlockState();
            };
        }

        return switch (island.type) {

            case DESERT ->
                    Blocks.SANDSTONE.defaultBlockState();

            case BADLANDS ->
                    Blocks.TERRACOTTA.defaultBlockState();

            case SNOW ->
                    Blocks.STONE.defaultBlockState();

            case STONY ->
                    Blocks.STONE.defaultBlockState();

            case FOREST ->
                    Blocks.STONE.defaultBlockState();

            case PLAINS ->
                    Blocks.STONE.defaultBlockState();

            case JUNGLE ->
                    Blocks.STONE.defaultBlockState();

            case SWAMP ->
                    Blocks.STONE.defaultBlockState();

            case DEFAULT ->
                    Blocks.STONE.defaultBlockState();
        };
    }

    /*
     * ============================================================
     * DISTANCE
     * ============================================================
     */

    private double getNormalizedDistance(
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
                        z * 1.17
                                + offset * 1.37
                );

        double c =
                Math.sin(
                        (x + z) * 0.73
                                + offset * 0.61
                );

        double d =
                Math.cos(
                        (x - z) * 1.43
                                - offset * 0.91
                );

        double e =
                Math.sin(
                        x * 2.31
                                + z * 0.57
                                + offset * 1.73
                );

        return
                a * 0.25
                        + b * 0.20
                        + c * 0.20
                        + d * 0.20
                        + e * 0.15;
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
     * ============================================================
     * STRUKTUREN
     * ============================================================
     *
     * Vorerst deaktiviert, damit keine Trail Chambers, Strongholds
     * oder Mineshafts im Void schweben.
     *
     * Im nächsten Schritt können wir hier gezielt eigene,
     * biomabhängige Strukturen hinzufügen.
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
     * CARVER
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
     * SURFACE
     * ============================================================
     */

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        // Oberfläche wird bereits erzeugt.
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
        // Später über Biome.
    }

    /*
     * ============================================================
     * HEIGHT / COLUMNS
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

            for (int y = island.bottomY; y <= surface; y++) {

                if (!isInsideIsland(
                        island,
                        x,
                        y,
                        z
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
                        getBlockForIsland(
                                island,
                                y,
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
                "Island groups: ~850 block grid"
        );

        result.add(
                "Main + small islands"
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
     * INSELTYPEN
     * ============================================================
     *
     * Diese bilden momentan die Grundlage für die verschiedenen
     * Inselarten. Der nächste Schritt ist, sie mit den echten
     * Minecraft-Biomen zu verbinden, damit nicht nur die Blöcke,
     * sondern auch das tatsächlich gesetzte Biom passt.
     */

    private enum IslandType {

        DEFAULT,
        PLAINS,
        FOREST,
        JUNGLE,
        DESERT,
        BADLANDS,
        SNOW,
        STONY,
        SWAMP
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
            double mountainStrength,
            IslandType type
    ) {
    }
}
