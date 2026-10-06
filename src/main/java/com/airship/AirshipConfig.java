package com.airship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings that can be changed in {@code config/airship.json} (created with the defaults on the first start).
 * Values are read once when the game starts and checked for sensible limits.
 * <p>
 * On a server, client and server should use the same file: the screens show the values from the player's own file.
 */
public final class AirshipConfig {
    /** Largest ship, in blocks. */
    public int maxBlocks = 2000;
    /** One balloon is needed per this many ship blocks. */
    public int blocksPerBalloon = 5;
    /** Top speeds in blocks per second: without fuel, with fuel, with turbo. */
    public double speedNoFuel = 2.5;
    public double speedFuel = 5.0;
    public double speedTurbo = 15.0;
    /** Size of the normal tank (minutes of thrust) and of the turbo tank (minutes of turbo). */
    public int tankMinutes = 60;
    public int turboTankMinutes = 10;
    /** The turbo tank burns this many times as fast as the normal one. */
    public int turboBurnRate = 5;

    private static AirshipConfig instance;

    public static synchronized AirshipConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static AirshipConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("airship.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        AirshipConfig config = new AirshipConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                AirshipConfig read = gson.fromJson(reader, AirshipConfig.class);
                if (read != null) {
                    config = read;
                }
            } catch (Exception e) {
                AirshipMod.LOGGER.warn("Could not read {}, using the defaults: {}", path, e.toString());
            }
        }
        config.sanitize();
        try {
            Files.writeString(path, gson.toJson(config)); // writes the defaults, or the corrected values
        } catch (IOException e) {
            AirshipMod.LOGGER.warn("Could not write {}: {}", path, e.toString());
        }
        return config;
    }

    private void sanitize() {
        maxBlocks = Math.max(10, Math.min(8000, maxBlocks));
        blocksPerBalloon = Math.max(1, Math.min(64, blocksPerBalloon));
        speedNoFuel = Math.max(0.5, Math.min(40.0, speedNoFuel));
        speedFuel = Math.max(speedNoFuel, Math.min(40.0, speedFuel));
        speedTurbo = Math.max(speedFuel, Math.min(40.0, speedTurbo));
        turboBurnRate = Math.max(1, Math.min(20, turboBurnRate));
        // The tanks are sent to the screens in seconds, as 16-bit numbers: keep them below 32000 seconds.
        tankMinutes = Math.max(1, Math.min(500, tankMinutes));
        turboTankMinutes = Math.max(1, Math.min(32000 / (60 * turboBurnRate), turboTankMinutes));
    }

    /** Size of the normal tank, in ticks. */
    public int maxFuelTicks() {
        return tankMinutes * 60 * 20;
    }

    /** Size of the turbo tank, in ticks (it burns {@code turboBurnRate} times as fast). */
    public int maxTurboTicks() {
        return turboTankMinutes * 60 * 20 * turboBurnRate;
    }
}
