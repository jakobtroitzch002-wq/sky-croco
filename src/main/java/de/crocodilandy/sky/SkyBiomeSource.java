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
        if (biomes.size() < 40) {
            throw new IllegalArgumentException("SkyBiomeSource requires 40 biomes");
        }
        this.biomes = List.copyOf(biomes);
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.stream();
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

        int groupX = Math.floorDiv(blockX, 850);
        int groupZ = Math.floorDiv(blockZ, 850);
        int variantSeed = mix(groupX, groupZ);

        int index = switch (type) {
            case SkyChunkGenerator.BIOME_MESA -> pick(variantSeed, 0, 30, 31);
            case SkyChunkGenerator.BIOME_TAIGA -> pick(variantSeed, 1, 22, 23);
            case SkyChunkGenerator.BIOME_MOUNTAINS -> pick(variantSeed, 2, 25, 26, 34, 35, 36);
            case SkyChunkGenerator.BIOME_DESERT -> 3;
            case SkyChunkGenerator.BIOME_FOREST -> pick(variantSeed, 4, 18, 19, 20, 37);
            case SkyChunkGenerator.BIOME_SNOW -> pick(variantSeed, 5, 16, 23, 34, 35);
            case SkyChunkGenerator.BIOME_MUSHROOM -> 6;
            case SkyChunkGenerator.BIOME_ROCKY -> pick(variantSeed, 7, 38);
            case SkyChunkGenerator.BIOME_PLAINS -> pick(variantSeed, 8, 15, 32);
            case SkyChunkGenerator.BIOME_JUNGLE -> pick(variantSeed, 9, 28, 29);
            case SkyChunkGenerator.BIOME_SWAMP -> pick(variantSeed, 10, 17);
            case SkyChunkGenerator.BIOME_SAVANNA -> pick(variantSeed, 11, 24, 27);
            case SkyChunkGenerator.BIOME_CHERRY -> 12;
            case SkyChunkGenerator.BIOME_BIRCH -> pick(variantSeed, 13, 20);
            default -> 8;
        };

        return biomes.get(index);
    }

    private static int pick(int seed, int... values) {
        return values[Math.floorMod(seed, values.length)];
    }

    private static int mix(int x, int z) {
        int value = x * 0x45d9f3b + z * 0x119de1f3;
        value ^= value >>> 16;
        value *= 0x45d9f3b;
        value ^= value >>> 16;
        return value;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
