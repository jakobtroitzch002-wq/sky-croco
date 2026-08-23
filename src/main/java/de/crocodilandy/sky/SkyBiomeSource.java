package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class SkyBiomeSource extends BiomeSource {

    public static final MapCodec<SkyBiomeSource> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            RegistryOps.retrieveGetter(Registries.BIOME)
                    ).apply(instance, SkyBiomeSource::new)
            );

    private final HolderGetter<Biome> biomes;

    public SkyBiomeSource(HolderGetter<Biome> biomes) {
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(
                biomes.getOrThrow(Biomes.BADLANDS),
                biomes.getOrThrow(Biomes.TAIGA),
                biomes.getOrThrow(Biomes.STONY_PEAKS),
                biomes.getOrThrow(Biomes.DESERT),
                biomes.getOrThrow(Biomes.FOREST),
                biomes.getOrThrow(Biomes.SNOWY_PLAINS),
                biomes.getOrThrow(Biomes.MUSHROOM_FIELDS),
                biomes.getOrThrow(Biomes.WINDSWEPT_HILLS)
        );
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

        return switch (SkyChunkGenerator.getBiomeTypeForPosition(blockX, blockZ)) {
            case SkyChunkGenerator.BIOME_MESA ->
                    biomes.getOrThrow(Biomes.BADLANDS);
            case SkyChunkGenerator.BIOME_TAIGA ->
                    biomes.getOrThrow(Biomes.TAIGA);
            case SkyChunkGenerator.BIOME_MOUNTAINS ->
                    biomes.getOrThrow(Biomes.STONY_PEAKS);
            case SkyChunkGenerator.BIOME_DESERT ->
                    biomes.getOrThrow(Biomes.DESERT);
            case SkyChunkGenerator.BIOME_FOREST ->
                    biomes.getOrThrow(Biomes.FOREST);
            case SkyChunkGenerator.BIOME_SNOW ->
                    biomes.getOrThrow(Biomes.SNOWY_PLAINS);
            case SkyChunkGenerator.BIOME_MUSHROOM ->
                    biomes.getOrThrow(Biomes.MUSHROOM_FIELDS);
            case SkyChunkGenerator.BIOME_ROCKY ->
                    biomes.getOrThrow(Biomes.WINDSWEPT_HILLS);
            default ->
                    biomes.getOrThrow(Biomes.FOREST);
        };
    }
}
