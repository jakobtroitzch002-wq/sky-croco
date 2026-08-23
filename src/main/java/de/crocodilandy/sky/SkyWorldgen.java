package de.crocodilandy.sky;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public final class SkyWorldgen {

    public static final Identifier SKY_GENERATOR_ID =
            Identifier.fromNamespaceAndPath(
                    "crocodilandy_sky",
                    "sky"
            );

    public static final Identifier SKY_BIOME_SOURCE_ID =
            Identifier.fromNamespaceAndPath(
                    "crocodilandy_sky",
                    "sky_biomes"
            );

    private SkyWorldgen() {
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.CHUNK_GENERATOR,
                SKY_GENERATOR_ID,
                SkyChunkGenerator.CODEC
        );

        Registry.register(
                BuiltInRegistries.BIOME_SOURCE,
                SKY_BIOME_SOURCE_ID,
                SkyBiomeSource.CODEC
        );
    }
}
