package com.airship;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side buffer for ship data that arrives before the ship entity exists on the client.
 * Only touched from the client main thread.
 */
public final class AirshipPendingCells {
    private static final Map<Integer, AirshipBlocksPayload> PENDING = new HashMap<>();

    private AirshipPendingCells() {}

    public static void put(int entityId, AirshipBlocksPayload payload) {
        PENDING.put(entityId, payload);
    }

    public static AirshipBlocksPayload take(int entityId) {
        return PENDING.remove(entityId);
    }

    public static void clear() {
        PENDING.clear();
    }
}
