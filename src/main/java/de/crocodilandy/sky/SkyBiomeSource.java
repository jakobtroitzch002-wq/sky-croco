// ERSETZE DEN KOMPLETTEN INHALT VON:
// src/main/java/de/crocodilandy/sky/SkyBiomeSource.java
//
// WICHTIG:
// Die bisherige Version hatte im CODEC null-Biome.
// Genau das verursachte den Server-Crash bei BiomeSource.possibleBiomes.
//
// Diese Version verwendet stattdessen einen Vanilla-BiomeSource als Grundlage.
// Dadurch sind immer echte Biome vorhanden und niemals null.

package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public final class SkyBiomeSource extends BiomeSource {

    private final BiomeSource fallbackBiomeSource;

    public SkyBiomeSource(BiomeSource fallbackBiomeSource) {
        this.fallbackBiomeSource = fallbackBiomeSource;
    }

    public static final MapCodec<SkyBiomeSource> CODEC =
            BiomeSource.CODEC
                    .fieldOf("fallback")
                    .xmap(
                            SkyBiomeSource::new,
                            source -> source.fallbackBiomeSource
                    );

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return fallbackBiomeSource.possibleBiomes().stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(
            int blockX,
            int blockY,
            int blockZ,
            Climate.Sampler sampler
    ) {
        return fallbackBiomeSource.getNoiseBiome(
                blockX,
                blockY,
                blockZ,
                sampler
        );
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }
}
