package com.airship;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    public static final MenuType<AirshipEngineMenu> ENGINE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(AirshipMod.MOD_ID, "airship_engine"),
            new MenuType<>(AirshipEngineMenu::new, FeatureFlags.VANILLA_SET)
    );

    private ModMenus() {}

    public static void initialize() {}
}
