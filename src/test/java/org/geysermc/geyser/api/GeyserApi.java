package org.geysermc.geyser.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GeyserApi {

    private static final GeyserApi INSTANCE = new GeyserApi();
    private static final Set<UUID> BEDROCK_PLAYERS = ConcurrentHashMap.newKeySet();

    private GeyserApi() {
    }

    public static GeyserApi api() {
        return INSTANCE;
    }

    public boolean isBedrockPlayer(UUID uuid) {
        return BEDROCK_PLAYERS.contains(uuid);
    }

    public static void setBedrockPlayer(UUID uuid, boolean bedrockPlayer) {
        if (bedrockPlayer) {
            BEDROCK_PLAYERS.add(uuid);
        } else {
            BEDROCK_PLAYERS.remove(uuid);
        }
    }
}
