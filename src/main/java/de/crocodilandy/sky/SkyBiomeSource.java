package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class SkyBiomeSource extends BiomeSource {

    private final long seed;
    private final Holder<Biome> mesa;
    private final Holder<Biome> taiga;
    private final Holder<Biome> mountains;
    private final Holder<Biome> desert;
    private final Holder<Biome> forest;
    private final Holder<Biome> snow;
    private final Holder<Biome> mushroom;
    private final Holder<Biome> rocky;

    public SkyBiomeSource(
            long seed,
            Holder<Biome> mesa,
            Holder<Biome> taiga,
            Holder<Biome> mountains,
            Holder<Biome> desert,
            Holder<Biome> forest,
            Holder<Biome> snow,
            Holder<Biome> mushroom,
            Holder<Biome> rocky
    ) {
        this.seed = seed;
        this.mesa = mesa;
        this.taiga = taiga;
        this.mountains = mountains;
        this.desert = desert;
        this.forest = forest;
        this.snow = snow;
        this.mushroom = mushroom;
        this.rocky = rocky;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(mesa, taiga, mountains, desert, forest, snow, mushroom, rocky);
    }

    @Override
    public Holder<Biome> getNoiseBiome(
            int blockX,
            int blockY,
            int blockZ,
            Climate.Sampler sampler
    ) {
        return switch (SkyChunkGenerator.getBiomeTypeForPosition(seed, blockX, blockZ)) {
            case SkyChunkGenerator.BIOME_MESA -> mesa;
            case SkyChunkGenerator.BIOME_TAIGA -> taiga;
            case SkyChunkGenerator.BIOME_MOUNTAINS -> mountains;
            case SkyChunkGenerator.BIOME_DESERT -> desert;
            case SkyChunkGenerator.BIOME_FOREST -> forest;
            case SkyChunkGenerator.BIOME_SNOW -> snow;
            case SkyChunkGenerator.BIOME_MUSHROOM -> mushroom;
            case SkyChunkGenerator.BIOME_ROCKY -> rocky;
            default -> forest;
        };
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    public static final MapCodec<SkyBiomeSource> CODEC =
            MapCodec.unit(new SkyBiomeSource(
                    0L,
                    null, null, null, null,
                    null, null, null, null
            ));
}
