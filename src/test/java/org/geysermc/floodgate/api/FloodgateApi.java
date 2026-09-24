package org.geysermc.floodgate.api;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FloodgateApi {

    private static final FloodgateApi INSTANCE = new FloodgateApi();
    private static final Set<UUID> FLOODGATE_PLAYERS = ConcurrentHashMap.newKeySet();

    static {
        FLOODGATE_PLAYERS.add(UUID.fromString("123e4567-e89b-42d3-a456-426614174001"));
    }

    private FloodgateApi() {
    }

    public static FloodgateApi getInstance() {
        return INSTANCE;
    }

    public boolean isFloodgatePlayer(UUID uuid) {
        return FLOODGATE_PLAYERS.contains(uuid);
    }

    public static void setFloodgatePlayer(UUID uuid, boolean floodgatePlayer) {
        if (floodgatePlayer) {
            FLOODGATE_PLAYERS.add(uuid);
        } else {
            FLOODGATE_PLAYERS.remove(uuid);
        }
    }
}
