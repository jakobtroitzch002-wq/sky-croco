package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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

    private static final int SURFACE_Y = 100;
    private static final int MIN_SURFACE_Y = 90;
    private static final int MAX_SURFACE_Y = 115;

    private static final int MIN_BOTTOM_Y = 42;
    private static final int MAX_BOTTOM_Y = 65;

    private static final int WORLD_MIN_Y = -64;

    /*
     * Inselgruppen:
     * Bei 32 Chunks Sichtweite soll normalerweise ungefähr
     * eine weitere Gruppe sichtbar sein, aber nicht überall
     * dicht gedrängt Inseln stehen.
     */
    private static final int GROUP_CELL_SIZE = 850;
    private static final double GROUP_CHANCE = 0.78;

    /*
     * Pro Gruppe:
     * genau eine große Hauptinsel
     * und mehrere kleine Neben-/Satelliteninseln.
     */
    private static final int MIN_SMALL_ISLANDS = 5;
    private static final int MAX_SMALL_ISLANDS = 10;

    private static final double MAIN_MIN_RADIUS = 34.0;
    private static final double MAIN_MAX_RADIUS = 58.0;

    private static final double SMALL_MIN_RADIUS = 7.0;
    private static final double SMALL_MAX_RADIUS = 24.0;

    private static final double GROUP_RADIUS = 210.0;

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

        List<Island> islands = findNearbyIslands(
                worldStartX + 8,
                worldStartZ + 8
        );

        for (int localX = 0; localX < 16; localX++) {

            int worldX = worldStartX + localX;

            for (int localZ = 0; localZ < 16; localZ++) {

                int worldZ = worldStartZ + localZ;

                int highestSurface = Integer.MIN_VALUE;

                for (Island island : islands) {

                    int surface = getSurfaceHeight(
                            island,
                            worldX,
                            worldZ
                    );

                    if (surface > highestSurface) {
                        highestSurface = surface;
                    }
                }

                if (highestSurface == Integer.MIN_VALUE) {
                    continue;
                }

                Holder<Biome> biome =
                        biomeSource.getNoiseBiome(
                                worldX >> 2,
                                SURFACE_Y >> 2,
                                worldZ >> 2,
                                randomState.sampler()
                        );

                for (int y = MIN_BOTTOM_Y; y <= highestSurface; y++) {

                    Island bestIsland = null;
                    double bestDistance = Double.MAX_VALUE;

                    for (Island island : islands) {

                        double distance = horizontalDistance(
                                island,
                                worldX,
                                worldZ
                        );

                        if (distance > island.radius * 1.35) {
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

                    int surface = getSurfaceHeight(
                            bestIsland,
                            worldX,
                            worldZ
                    );

                    if (surface == Integer.MIN_VALUE || y > surface) {
                        continue;
                    }

                    BlockState state = getBiomeBlock(
                            biome,
                            worldX,
                            y,
                            worldZ,
                            surface,
                            bestIsland
                    );

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
     * BIOME-ABHÄNGIGE BLOCKSCHICHTEN
     * ============================================================
     */

    private BlockState getBiomeBlock(
            Holder<Biome> biome,
            int x,
            int y,
            int z,
            int surface,
            Island island
    ) {
        String biomeName = biome.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("");

        boolean badlands =
                biomeName.contains("badlands")
                        || biomeName.contains("mesa");

        boolean desert =
                biomeName.contains("desert");

        boolean snowy =
                biomeName.contains("snow")
                        || biomeName.contains("frozen")
                        || biomeName.contains("ice");

        boolean taiga =
                biomeName.contains("taiga")
                        || biomeName.contains("grove");

        boolean stony =
                biomeName.contains("stony")
                        || biomeName.contains("jagged")
                        || biomeName.contains("peaks")
                        || biomeName.contains("mountain");

        /*
         * BADLANDS / MESA
         *
         * Mehrere farbige Terrakotta-Ringe statt nur
         * normaler Terrakotta.
         */
        if (badlands) {

            if (y >= surface - 1) {
                return getTerracottaLayer(y, island);
            }

            if (y >= surface - 18) {
                return getTerracottaLayer(y, island);
            }

            if (y >= surface - 28) {
                return Blocks.TERRACOTTA.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Wüste
         */
        if (desert) {

            if (y >= surface - 2) {
                return Blocks.SAND.defaultBlockState();
            }

            if (y >= surface - 7) {
                return Blocks.SANDSTONE.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Schnee-/Eisbiome
         */
        if (snowy) {

            if (y == surface) {
                return Blocks.SNOW_BLOCK.defaultBlockState();
            }

            if (y >= surface - 3) {
                return Blocks.DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Taiga
         */
        if (taiga) {

            if (y == surface) {
                return Blocks.PODZOL.defaultBlockState();
            }

            if (y >= surface - 4) {
                return Blocks.DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Steinige Gebirge / Peaks.
         */
        if (stony) {

            if (y >= surface - 1) {
                return Blocks.STONE.defaultBlockState();
            }

            if (y >= surface - 4) {
                return Blocks.DIRT.defaultBlockState();
            }

            return Blocks.STONE.defaultBlockState();
        }

        /*
         * Standard:
         * Plains, Forest, River, Jungle usw.
         */
        if (y >= surface - 1) {
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (y >= surface - 4) {
            return Blocks.DIRT.defaultBlockState();
        }

        return Blocks.STONE.defaultBlockState();
    }

    private BlockState getTerracottaLayer(
            int y,
            Island island
    ) {
        int layer = Math.floorMod(
                y
                        + (int) island.noiseOffset
                        + (int) (terrainNoise(
                        island.x,
                        island.z,
                        island.noiseOffset
                ) * 6.0),
                24
        );

        if (layer < 4) {
            return Blocks.TERRACOTTA.defaultBlockState();
        }

        if (layer < 7) {
            return Blocks.ORANGE_TERRACOTTA.defaultBlockState();
        }

        if (layer < 10) {
            return Blocks.YELLOW_TERRACOTTA.defaultBlockState();
        }

        if (layer < 13) {
            return Blocks.RED_TERRACOTTA.defaultBlockState();
        }

        if (layer < 16) {
            return Blocks.WHITE_TERRACOTTA.defaultBlockState();
        }

        if (layer < 19) {
            return Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
        }

        if (layer < 21) {
            return Blocks.BROWN_TERRACOTTA.defaultBlockState();
        }

        return Blocks.TERRACOTTA.defaultBlockState();
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

        for (int offsetX = -1; offsetX <= 1; offsetX++) {

            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {

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

                if (random.nextDouble() > GROUP_CHANCE) {
                    continue;
                }

                double centerX =
                        groupX * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 260.0
                                - 130.0;

                double centerZ =
                        groupZ * GROUP_CELL_SIZE
                                + GROUP_CELL_SIZE / 2.0
                                + random.nextDouble() * 260.0
                                - 130.0;

                /*
                 * Genau eine große Hauptinsel.
                 */
                double mainRadius =
                        MAIN_MIN_RADIUS
                                + random.nextDouble()
                                * (MAIN_MAX_RADIUS
                                - MAIN_MIN_RADIUS);

                result.add(
                        createIsland(
                                random,
                                centerX,
                                centerZ,
                                mainRadius,
                                true
                        )
                );

                /*
                 * Mehrere deutlich kleinere Inseln.
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
                     * Kleine Inseln näher um die Hauptinsel.
                     */
                    double distance =
                            45.0
                                    + Math.pow(
                                    random.nextDouble(),
                                    0.72
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
                            SMALL_MIN_RADIUS
                                    + random.nextDouble()
                                    * (SMALL_MAX_RADIUS
                                    - SMALL_MIN_RADIUS);

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

        double rotation =
                random.nextDouble()
                        * Math.PI
                        * 2.0;

        double stretchX =
                0.72
                        + random.nextDouble()
                        * 0.65;

        double stretchZ =
                0.72
                        + random.nextDouble()
                        * 0.65;

        double noiseOffset =
                random.nextDouble()
                        * 100000.0;

        /*
         * Jede Insel bekommt eigene Berge.
         * Hauptinseln deutlich stärkere.
         */
        double mountainStrength =
                mainIsland
                        ? 8.0 + random.nextDouble() * 16.0
                        : 2.0 + random.nextDouble() * 7.0;

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
                mainIsland
        );
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
                horizontalDistance(
                        island,
                        x,
                        z
                );

        /*
         * Küstenrand wird zusätzlich durch Noise verformt.
         */
        double coastNoise =
                terrainNoise(
                        x * 0.70,
                        z * 0.70,
                        island.noiseOffset + 17.0
                ) * 0.18;

        double normalized =
                distance / island.radius;

        normalized -= coastNoise;

        if (normalized > 1.0) {
            return Integer.MIN_VALUE;
        }

        normalized =
                Math.max(
                        0.0,
                        normalized
                );

        double edge =
                normalized;

        double broadNoise =
                terrainNoise(
                        x * 0.40,
                        z * 0.40,
                        island.noiseOffset
                );

        double detailNoise =
                terrainNoise(
                        x * 1.35,
                        z * 1.35,
                        island.noiseOffset + 341.7
                );

        /*
         * Große, einzelne Bergformen.
         *
         * Dadurch entstehen nicht überall gleichmäßige
         * Erdhügel, sondern einzelne deutlichere Berge.
         */
        double mountainNoise =
                ridgeNoise(
                        x,
                        z,
                        island.noiseOffset + 900.0
                );

        double mountainMask =
                Math.max(
                        0.0,
                        1.0 - edge * 1.25
                );

        mountainMask =
                Math.pow(
                        mountainMask,
                        1.7
                );

        double mountains =
                mountainNoise
                        * island.mountainStrength
                        * mountainMask;

        /*
         * Kleine Inseln bleiben deutlich ruhiger.
         */
        if (!island.mainIsland) {
            mountains *= 0.45;
        }

        double edgeVariation =
                terrainNoise(
                        x * 1.9,
                        z * 1.9,
                        island.noiseOffset + 513.0
                )
                        * edge
                        * 4.0;

        double surfaceVariation =
                broadNoise * 3.5
                        + detailNoise * 2.5
                        + edgeVariation
                        + mountains;

        /*
         * Leichte natürliche Erhöhung zum Zentrum.
         */
        double broadShape =
                Math.pow(
                        Math.max(
                                0.0,
                                1.0 - edge
                        ),
                        1.6
                )
                        * 2.5;

        int result =
                (int) Math.round(
                        island.surfaceY
                                + surfaceVariation
                                + broadShape
                );

        return MthClamp(
                result,
                MIN_SURFACE_Y,
                MAX_SURFACE_Y + 22
        );
    }

    /*
     * ============================================================
     * INSELKÖRPER
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

        if (surface == Integer.MIN_VALUE
                || y > surface
                || y < island.bottomY) {
            return 0.0;
        }

        double normalized =
                (double) (y - island.bottomY)
                        / Math.max(
                        1,
                        surface - island.bottomY
                );

        /*
         * Oben breit, nach unten sehr schnell schmaler.
         */
        double verticalShape =
                0.035
                        + Math.pow(
                        normalized,
                        0.48
                )
                        * 0.965;

        /*
         * Organische Unterseite.
         */
        double lowerNoise =
                terrainNoise(
                        x * 0.65,
                        z * 0.65,
                        island.noiseOffset
                                + y * 0.17
                );

        double mediumNoise =
                terrainNoise(
                        x * 1.20,
                        z * 1.20,
                        island.noiseOffset
                                + y * 0.29
                );

        double noiseStrength =
                (1.0 - normalized);

        double rockVariation =
                lowerNoise
                        * island.radius
                        * 0.12
                        * noiseStrength
                        + mediumNoise
                        * island.radius
                        * 0.055
                        * noiseStrength;

        return Math.max(
                1.5,
                island.radius
                        * verticalShape
                        + rockVariation
        );
    }

    private double horizontalDistance(
            Island island,
            int x,
            int z
    ) {
        double dx = x - island.x;
        double dz = z - island.z;

        double cos =
                Math.cos(island.rotation);

        double sin =
                Math.sin(island.rotation);

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
                        x * 0.018
                                + offset
                );

        double b =
                Math.sin(
                        z * 0.023
                                + offset * 1.37
                );

        double c =
                Math.sin(
                        (x + z) * 0.011
                                + offset * 0.73
                );

        double d =
                Math.cos(
                        (x - z) * 0.007
                                - offset * 1.11
                );

        double e =
                Math.sin(
                        x * 0.075
                                + z * 0.051
                                + offset * 0.31
                );

        return
                a * 0.27
                        + b * 0.23
                        + c * 0.20
                        + d * 0.18
                        + e * 0.12;
    }

    /*
     * Für einzelne deutlichere Bergformen.
     */
    private double ridgeNoise(
            double x,
            double z,
            double offset
    ) {
        double a =
                terrainNoise(
                        x * 0.32,
                        z * 0.32,
                        offset
                );

        double b =
                terrainNoise(
                        x * 0.75,
                        z * 0.75,
                        offset + 200.0
                );

        double ridge =
                1.0 - Math.abs(
                        a * 0.75
                                + b * 0.25
                );

        ridge =
                Math.max(
                        0.0,
                        ridge
                );

        return Math.pow(
                ridge,
                5.0
        );
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
         * Vorläufig deaktiviert, damit keine Trial Chambers,
         * Strongholds oder Mineshafts frei im Void schweben.
         */
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
        // Keine Carver.
    }

    @Override
    public void buildSurface(
            WorldGenRegion level,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess protoChunk
    ) {
        // Oberfläche wird direkt erzeugt.
    }

    @Override
    public void spawnOriginalMobs(
            WorldGenRegion worldGenRegion
    ) {
        // Vanilla-Mob-Generierung später.
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

        for (int y = MIN_BOTTOM_Y;
             y < heightAccessor.getMinY()
                     + heightAccessor.getHeight();
             y++) {

            Island bestIsland = null;
            double bestDistance = Double.MAX_VALUE;

            for (Island island : islands) {

                double distance =
                        horizontalDistance(
                                island,
                                x,
                                z
                        );

                if (distance > island.radius * 1.35) {
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

            int index = y - minY;

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
                "One large island + small satellites"
        );

        result.add(
                "Biome terrain: enabled"
        );

        result.add(
                "Badlands layers: enabled"
        );

        result.add(
                "Structures: disabled"
        );
    }

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
            boolean mainIsland
    ) {
    }
}
