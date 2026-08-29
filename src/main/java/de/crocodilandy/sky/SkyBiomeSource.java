package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.stream.Stream;

public final class SkyBiomeSource extends BiomeSource {

    public static final MapCodec<SkyBiomeSource> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            Biome.CODEC.listOf().fieldOf("biomes")
                                    .forGetter(source -> source.biomes)
                    ).apply(instance, SkyBiomeSource::new)
            );

    private final List<Holder<Biome>> biomes;

    public SkyBiomeSource(List<Holder<Biome>> biomes) {
        if (biomes.size() < 15) {
            throw new IllegalArgumentException("SkyBiomeSource requires 15 biomes");
        }
        this.biomes = List.copyOf(biomes);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream().limit(15);
    }

    @Override
    public Holder<Biome> getNoiseBiome(
            int quartX,
            int quartY,
            int quartZ,
            Climate.Sampler sampler
    ) {
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;

        int type = SkyChunkGenerator.getBiomeTypeForPosition(
                SkyChunkGenerator.getCurrentWorldSeed(),
                blockX,
                blockZ
        );

        if (type < 0) {
            return biomes.get(14);
        }

        if (type >= 14) {
            type = SkyChunkGenerator.BIOME_FOREST;
        }

        return biomes.get(type);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
