package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class SkyBiomeSource extends BiomeSource {

    public static final ResourceKey<Biome> MESA = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "badlands")
    );

    public static final ResourceKey<Biome> TAIGA = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "taiga")
    );

    public static final ResourceKey<Biome> MOUNTAINS = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "stony_peaks")
    );

    public static final ResourceKey<Biome> DESERT = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "desert")
    );

    public static final ResourceKey<Biome> FOREST = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "forest")
    );

    public static final ResourceKey<Biome> SNOW = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "snowy_plains")
    );

    public static final ResourceKey<Biome> MUSHROOM = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "mushroom_fields")
    );

    public static final ResourceKey<Biome> ROCKY = ResourceKey.create(
            BuiltInRegistries.BIOME.key(),
            net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "windswept_hills")
    );

    public static final MapCodec<SkyBiomeSource> CODEC =
            RecordCodecBuilder.mapCodec(instance ->
                    instance.group(
                            Biome.CODEC.listOf().fieldOf("biomes")
                                    .forGetter(source -> source.biomes.stream().toList())
                    ).apply(instance, list -> new SkyBiomeSource(list))
            );

    private final java.util.List<Holder<Biome>> biomes;

    public SkyBiomeSource(java.util.List<Holder<Biome>> biomes) {
        this.biomes = java.util.List.copyOf(biomes);
    }

    public SkyBiomeSource(RegistryAccess registries) {
        this.biomes = java.util.List.of(
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(MESA),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(TAIGA),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(MOUNTAINS),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(DESERT),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(FOREST),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(SNOW),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(MUSHROOM),
                registries.registryOrThrow(BuiltInRegistries.BIOME.key()).getOrThrow(ROCKY)
        );
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

        int type = SkyChunkGenerator.getBiomeTypeForPosition(0L, blockX, blockZ);
        if (type < 0 || type >= biomes.size()) {
            type = SkyChunkGenerator.BIOME_FOREST;
        }

        return biomes.get(type);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
