package de.crocodilandy.sky;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public final class SkyWorldgen {

    public static final ResourceLocation SKY_GENERATOR_ID =
            ResourceLocation.fromNamespaceAndPath(
                    "crocodilandy_sky",
                    "sky"
            );

    private SkyWorldgen() {
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.CHUNK_GENERATOR,
                SKY_GENERATOR_ID,
                SkyChunkGenerator.CODEC
        );
    }
}
