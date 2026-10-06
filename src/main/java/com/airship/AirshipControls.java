package com.airship;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Server-side storage of the latest control input of every player. */
public final class AirshipControls {
    /** Input older than this many ticks is ignored, so a lost key-up can never leave the ship running. */
    private static final long MAX_AGE_TICKS = 10;

    private record Entry(int flags, long tick) {}

    private static final Map<UUID, Entry> INPUT = new HashMap<>();

    private AirshipControls() {}

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(
                AirshipControlPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    INPUT.put(
                            player.getUUID(),
                            new Entry(payload.flags() & AirshipControlPayload.ALL, player.level().getGameTime())
                    );
                }
        );
    }

    public static int flags(ServerPlayer player, long now) {
        Entry entry = INPUT.get(player.getUUID());
        if (entry == null || now - entry.tick() > MAX_AGE_TICKS) {
            return 0;
        }
        return entry.flags();
    }
}
