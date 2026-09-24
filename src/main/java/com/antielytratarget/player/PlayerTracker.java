package com.antielytratarget.player;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {

    private final Map<UUID, AETPlayer> players = new ConcurrentHashMap<>();

    public AETPlayer get(Player player) {
        UUID uuid = player.getUniqueId();
        AETPlayer current = players.get(uuid);
        if (current != null && current.player == player) {
            return current;
        }

        return players.compute(uuid, (key, existing) -> {

if (existing == null || existing.player != player) {
                return new AETPlayer(player);
            }
            return existing;
        });
    }

    public AETPlayer get(UUID uuid) {
        return players.get(uuid);
    }

    public void remove(UUID uuid) {
        players.remove(uuid);
    }

public boolean remove(Player player) {
        UUID uuid = player.getUniqueId();
        boolean[] removed = {false};
        players.computeIfPresent(uuid, (key, current) -> {
            if (current.player == player) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    public Map<UUID, AETPlayer> getAll() {
        return players;
    }

    public void clear() {
        players.clear();
    }
}
