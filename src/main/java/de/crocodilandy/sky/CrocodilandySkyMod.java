package de.crocodilandy.sky;

import net.fabricmc.api.ModInitializer;

public final class CrocodilandySkyMod implements ModInitializer {

    @Override
    public void onInitialize() {
        SkyWorldgen.register();
        SkyResourceGenerator.register();

        System.out.println("Crocodilandy Sky loaded");
    }
}
