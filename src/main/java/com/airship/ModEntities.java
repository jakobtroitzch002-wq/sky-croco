package com.airship;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final ResourceKey<EntityType<?>> AIRSHIP_KEY = ResourceKey.create(
            Registries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship")
    );

    public static final EntityType<AirshipEntity> AIRSHIP = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            AIRSHIP_KEY,
            EntityType.Builder.of(AirshipEntity::new, MobCategory.MISC)
                    .sized(8.0F, 6.0F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build(AIRSHIP_KEY)
    );

    private ModEntities() {}

    public static void initialize() {}
}
