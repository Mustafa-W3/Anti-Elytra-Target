package com.antielytratarget.managers;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.models.FlagLogEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileManager {

    private final AntiElytraTargetPlugin              plugin;
    private final ConfigManager                       config;

private final Map<UUID, List<FlagLogEntry>> profiles = new ConcurrentHashMap<>();

    public ProfileManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        loadAllProfiles();
    }

public void addEntry(UUID uuid, FlagLogEntry entry) {
        List<FlagLogEntry> profile = profiles.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (profile) {
            profile.add(entry);
            int max = config.getMaxProfileEntries();
            while (profile.size() > max) profile.remove(0);
        }
    }

    public List<FlagLogEntry> getProfile(UUID uuid) {
        List<FlagLogEntry> profile = profiles.get(uuid);
        if (profile == null) return Collections.emptyList();
        synchronized (profile) {
            return Collections.unmodifiableList(new ArrayList<>(profile));
        }
    }

public Map.Entry<UUID, List<FlagLogEntry>> findByName(String name) {
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return Map.entry(player.getUniqueId(), getProfile(player.getUniqueId()));
            }
        }
        for (var entry : profiles.entrySet()) {
            List<FlagLogEntry> list = entry.getValue();
            synchronized (list) {
                if (!list.isEmpty() && list.get(0).getPlayerName().equalsIgnoreCase(name)) {
                    return Map.entry(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(list)));
                }
            }
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.getUniqueId() != null) {
            List<FlagLogEntry> profile = profiles.get(offline.getUniqueId());
            if (profile != null && !profile.isEmpty()) {
                synchronized (profile) {
                    return Map.entry(offline.getUniqueId(), Collections.unmodifiableList(new ArrayList<>(profile)));
                }
            }
        }
        return null;
    }

    public int     getTotalFlags(UUID uuid)   { return getProfile(uuid).size(); }
    public void    clearProfile(UUID uuid)    { profiles.remove(uuid); }
    public Set<UUID> getAllTrackedUUIDs()      { return Collections.unmodifiableSet(profiles.keySet()); }
    public int     getTrackedPlayerCount()    { return profiles.size(); }

public List<String> getAllTrackedNames() {
        List<String> names = new ArrayList<>();
        for (List<FlagLogEntry> list : profiles.values()) {
            synchronized (list) {
                if (!list.isEmpty()) names.add(list.get(0).getPlayerName());
            }
        }
        return names;
    }

public void loadAllProfiles() {
        Path logPath = plugin.getLogManager().getLogPath();
        if (!Files.exists(logPath)) {
            plugin.getLogger().info("[AET] No previous flag history found (first run or file missing).");
            return;
        }
        Map<String, UUID> knownPlayers = buildPlayerUuidIndex();
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(logPath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                FlagLogEntry entry = FlagLogEntry.fromLogLine(
                        line, name -> resolvePlayerUuid(name, knownPlayers));
                if (entry == null) continue;
                List<FlagLogEntry> profile = profiles.computeIfAbsent(
                        entry.getPlayerUUID(),
                        k -> Collections.synchronizedList(new ArrayList<>()));
                synchronized (profile) {
                    profile.add(entry);
                }
                loaded++;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[AET] Failed to load flag history: " + e.getMessage());
        }

        int max = config.getMaxProfileEntries();
        for (List<FlagLogEntry> profile : profiles.values()) {
            synchronized (profile) {
                while (profile.size() > max) profile.remove(0);
            }
        }
        plugin.getLogger().info("[AET] Loaded " + loaded + " historical flag entries for "
                + profiles.size() + " players.");
    }

    private Map<String, UUID> buildPlayerUuidIndex() {
        Map<String, UUID> players = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName();
            if (name != null) {
                players.putIfAbsent(name.toLowerCase(Locale.ROOT), player.getUniqueId());
            }
        }
        return players;
    }

    @SuppressWarnings("deprecation")
    private UUID resolvePlayerUuid(String playerName, Map<String, UUID> knownPlayers) {
        String key = playerName.toLowerCase(Locale.ROOT);
        UUID known = knownPlayers.get(key);
        if (known != null) return known;

        UUID resolved = Bukkit.getOfflinePlayer(playerName).getUniqueId();
        knownPlayers.put(key, resolved);
        return resolved;
    }

public void saveAllProfiles() {
        plugin.getLogger().info("[AET] Session ended. Total tracked players: " + profiles.size()
                + " | Total flags this session: " + profiles.values().stream()
                .mapToInt(List::size).sum());
    }
}
