package com.antielytratarget.managers;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.models.FlagLogEntry;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.models.PlayerFlagData;
import com.antielytratarget.utils.MessageUtil;
import com.antielytratarget.utils.PermissionUtil;
import com.antielytratarget.utils.SchedulerUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FlagManager {

    private final AntiElytraTargetPlugin plugin;
    private final ConfigManager          config;

    private final Map<UUID, PlayerFlagData> flagDataMap      = new ConcurrentHashMap<>();
    private final Set<UUID>                 alertsDisabled   = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer>        testModeHitCount = new ConcurrentHashMap<>();

    private final AtomicLong totalHitsProcessed  = new AtomicLong(0);
    private final AtomicLong totalFlagsGenerated = new AtomicLong(0);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    public FlagManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        SchedulerUtil.runTaskTimer(plugin, this::cleanupExpiredWindows, 20L * 5, 20L * 10);
    }

public PlayerFlagData getOrCreate(Player player) {
        UUID uuid = player.getUniqueId();
        return flagDataMap.computeIfAbsent(
                uuid, ignored -> new PlayerFlagData(uuid, player.getName()));
    }

    public PlayerFlagData get(UUID uuid) { return flagDataMap.get(uuid); }

    public Map<UUID, PlayerFlagData> getAllFlagData() {
        return Collections.unmodifiableMap(flagDataMap);
    }

public void processFlag(Player attacker, LivingEntity victim,
                               String checkName, double value) {
        processFlag(attacker, victim != null ? victim.getName() : null, checkName, value);
    }

    public void processFlag(Player attacker, String victimName,
                               String checkName, double value) {
        totalHitsProcessed.incrementAndGet();

Player currentPlayer = Bukkit.getPlayer(attacker.getUniqueId());
        if (!attacker.isOnline() || currentPlayer != attacker) {
            if (plugin.isDebugEnabled()) {
                plugin.debug("[FlagManager] Ignored stale-session flag for " + attacker.getName());
            }
            return;
        }

ConfigManager.HotSettings settings = config.getHotSettingsSnapshot();
        if (config.isPingExempt(attacker)) return;
        String bp = settings.bypassPermission();
        if (attacker.isPermissionSet(bp) && attacker.hasPermission(bp)) {
            if (plugin.isDebugEnabled()) {
                plugin.debug("[FlagManager] " + attacker.getName()
                        + " bypassed (" + checkName + ")");
            }
            return;
        }

        PlayerFlagData data = getOrCreate(attacker);
        long now = System.currentTimeMillis();
        PlayerFlagData.FlagUpdate flagUpdate;
        synchronized (data) {
            flagUpdate = data.recordFlag(now, settings.flagResetTime());
        }
        int total = flagUpdate.totalFlags();
        int window = flagUpdate.flagsInWindow();
        totalFlagsGenerated.incrementAndGet();

        String locationStr = formatLocation(attacker);
        String resolvedVictimName = victimName != null && !victimName.isBlank()
                ? victimName : "N/A";

FlagLogEntry entry = new FlagLogEntry(
                attacker.getName(), attacker.getUniqueId(),
                checkName, total,
                locationStr, resolvedVictimName, value);
        plugin.getProfileManager().addEntry(attacker.getUniqueId(), entry);
        plugin.getLogManager().log(
                entry, buildDebugDetails(attacker, window, settings));

logToConsole(attacker, checkName, total, window, value, locationStr, settings);
        notifyOps(attacker, resolvedVictimName, checkName,
                total, window, value, locationStr, settings);

        applyKickBan(attacker, data, total, settings);

        var recorder = plugin.getReplayRecorder();
        if (recorder != null) {
            recorder.onFlag(attacker, checkName, total);
        }

        if (plugin.isDebugEnabled()) {
            plugin.debug(String.format(
                    "[FlagManager] %s | %s | total=%d | LOGGED | val=%.4f",
                    attacker.getName(), checkName, total, value));
        }
    }

private void applyKickBan(Player player, PlayerFlagData data, int total,
                              ConfigManager.HotSettings settings) {
        int kickThreshold = Math.max(1, settings.kickThreshold());
        if (settings.kickEnabled() && total >= kickThreshold) {
            int crossings = total / kickThreshold;
            if (data.tryReserveKick(crossings)) {
                String kickNotify = settings.prefix() + config.getMsgNotifyKick()
                        .replace("{player}", player.getName())
                        .replace("{flags}", String.valueOf(total));
                Runnable kick = () -> executeKick(
                        player, data, total, crossings, settings, kickNotify);
                if (settings.kickCommandEnabled()) {

                    SchedulerUtil.runTask(plugin, kick);
                } else {

SchedulerUtil.runForEntity(plugin, player, kick, data::releaseKick);
                }
            }
        }

        if (settings.banEnabled() && total >= settings.banThreshold()) {
            String banNotify = settings.prefix() + config.getMsgNotifyBan()
                    .replace("{player}", player.getName())
                    .replace("{flags}", String.valueOf(total));
            Runnable ban = () -> executeBan(player, total, settings, banNotify);
            if (settings.banCommandEnabled()) {

                SchedulerUtil.runTask(plugin, ban);
            } else {
                SchedulerUtil.runForEntity(plugin, player, ban, null);
            }
        }
    }

    private void executeKick(Player scheduledPlayer, PlayerFlagData data,
                             int total, int crossings,
                             ConfigManager.HotSettings settings,
                             String kickNotify) {
        if (!settings.kickCommandEnabled()) {
            Player current = Bukkit.getPlayer(scheduledPlayer.getUniqueId());
            if (!scheduledPlayer.isOnline() || current != scheduledPlayer) {
                data.releaseKick();
                return;
            }
        }

        try {
            if (settings.kickCommandEnabled()) {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        settings.kickCommand().replace(
                                "{player}", scheduledPlayer.getName()));
                if (!dispatched) {
                    data.releaseKick();
                    plugin.getLogger().severe("[AET] Kick command could not be dispatched for "
                            + scheduledPlayer.getName());
                    return;
                }
            } else {
                scheduledPlayer.kickPlayer(settings.kickMessage());
            }

            data.completeKick(crossings);
            sendToNotify(kickNotify);
            plugin.getLogger().warning("[AET] KICK: " + scheduledPlayer.getName()
                    + " (total=" + total + ")");
        } catch (RuntimeException ex) {
            data.releaseKick();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "[AET] Failed to kick " + scheduledPlayer.getName(), ex);
        }
    }

    private void executeBan(Player scheduledPlayer, int total,
                            ConfigManager.HotSettings settings,
                            String banNotify) {
        if (!settings.banCommandEnabled()) {
            Player current = Bukkit.getPlayer(scheduledPlayer.getUniqueId());
            if (!scheduledPlayer.isOnline() || current != scheduledPlayer) return;
        }

        sendToNotify(banNotify);
        if (settings.banCommandEnabled()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    settings.banCommand().replace(
                            "{player}", scheduledPlayer.getName()));
        } else {
            long banMs = settings.banDurationMinutes() * 60_000L;
            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(
                    scheduledPlayer.getName(), settings.banMessage(),
                    new Date(System.currentTimeMillis() + banMs),
                    "AntiElytraTarget");
            scheduledPlayer.kickPlayer(settings.banMessage());
        }
        plugin.getLogger().warning("[AET] BAN: " + scheduledPlayer.getName()
                + " (total=" + total + ")");
    }

private void notifyOps(Player flagged, String victimName, String checkName,
                           int total, int window, double value, String locationStr,
                           ConfigManager.HotSettings settings) {
        String stageLabel;
        if (window >= settings.blockModeThreshold()) {
            stageLabel = "<red>BLOCK";
        } else if (window >= settings.testModeThreshold()) {
            stageLabel = "<yellow>TEST";
        } else {
            stageLabel = "<aqua>WATCH";
        }

        Location loc = flagged.getLocation();
        int ping;
        try {
            ping = flagged.getPing();
        } catch (NoSuchMethodError e) {
            ping = -1;
        }
        GameMode gm = flagged.getGameMode();

        Map<String, String> placeholders = new LinkedHashMap<>();
        placeholders.put("player", flagged.getName());
        placeholders.put("uuid", flagged.getUniqueId().toString());
        placeholders.put("ping", ping >= 0 ? String.valueOf(ping) : "N/A");
        placeholders.put("gamemode", gm.name());
        placeholders.put("world", loc.getWorld() != null ? loc.getWorld().getName() : "?");
        placeholders.put("x", String.format("%.1f", loc.getX()));
        placeholders.put("y", String.format("%.1f", loc.getY()));
        placeholders.put("z", String.format("%.1f", loc.getZ()));
        placeholders.put("check", checkName);
        placeholders.put("value", String.format("%.4f", value));
        placeholders.put("flags", String.valueOf(total));
        placeholders.put("window", String.valueOf(window));
        placeholders.put("stage", stageLabel);
        placeholders.put("victim", victimName);
        placeholders.put("time", LocalDateTime.now().format(TIME_FMT));
        placeholders.put("location", locationStr);

        MessageUtil.CompiledTemplate alertTemplate = settings.alertMessageTemplate();
        MessageUtil.CompiledTemplate hoverTemplate = settings.alertHoverTemplate();
        ClickEvent clickEvent = buildAlertClickEvent(placeholders, settings);

        Component hoverComponent = hoverTemplate.render(placeholders);
        Component rendered = alertTemplate.render(placeholders)
                .hoverEvent(HoverEvent.showText(hoverComponent));
        Component component = clickEvent != null
                ? rendered.clickEvent(clickEvent) : rendered;

        SchedulerUtil.forEachOnlinePlayer(plugin, op -> {
            if (PermissionUtil.canReceiveAlerts(op)
                    && !alertsDisabled.contains(op.getUniqueId())) {
                op.sendMessage(component);
            }
        });
    }

    private void sendToNotify(String message) {
        Component comp = MessageUtil.colorize(message);
        SchedulerUtil.forEachOnlinePlayer(plugin, op -> {
            if (PermissionUtil.canReceiveAlerts(op)) op.sendMessage(comp);
        });
    }

    private ClickEvent buildAlertClickEvent(
            Map<String, String> placeholders,
            ConfigManager.HotSettings settings) {
        if (!settings.alertClickEnabled()) return null;

        String value = MessageUtil.applyPlaceholders(
                settings.alertClickCommand(), placeholders);
        if (value == null || value.isBlank()) return null;

        String action = settings.alertClickAction();
        if (action == null || action.isBlank()) action = "run_command";
        action = action.toLowerCase(Locale.ROOT).replace('-', '_');

        return switch (action) {
            case "suggest", "suggest_command" -> ClickEvent.suggestCommand(value);
            case "open_url", "url" -> ClickEvent.openUrl(value);
            case "copy", "copy_to_clipboard" -> ClickEvent.copyToClipboard(value);
            case "run", "command", "run_command" -> ClickEvent.runCommand(value);
            default -> ClickEvent.runCommand(value);
        };
    }

private void logToConsole(Player attacker, String checkName,
                              int total, int window, double value,
                              String locationStr,
                              ConfigManager.HotSettings settings) {
        if (!settings.consoleLoggingEnabled()) return;
        String stage = window >= settings.blockModeThreshold() ? "BLOCK" :
                window >= settings.testModeThreshold() ? "TEST" : "WATCH";
        plugin.getLogger().warning(String.format(
                "[AET][%s] %s | %s | total=%d win=%d | val=%.4f | %s",
                stage, attacker.getName(), checkName,
                total, window, value, locationStr));
    }

    private String buildDebugDetails(
            Player player, int window, ConfigManager.HotSettings settings) {
        if (!settings.debugMode()) return "";

        String stage = window >= settings.blockModeThreshold() ? "BLOCK"
                : window >= settings.testModeThreshold() ? "TEST" : "WATCH";
        int ping;
        try {
            ping = player.getPing();
        } catch (NoSuchMethodError error) {
            ping = -1;
        }

        Vector velocity = player.getVelocity();
        double[] tps = plugin.getCurrentTpsSamples();
        return String.format(Locale.ROOT,
                "WINDOW=%d STAGE=%s PING=%s GAMEMODE=%s "
                        + "GLIDING=%s ON_GROUND=%s FLYING=%s "
                        + "VELOCITY=(%.4f,%.4f,%.4f) TPS=(%.2f,%.2f,%.2f) "
                        + "THRESHOLDS=(test=%d,block=%d)",
                window, stage, ping >= 0 ? ping + "ms" : "N/A",
                player.getGameMode().name(),
                player.isGliding(), player.isOnGround(), player.isFlying(),
                velocity.getX(), velocity.getY(), velocity.getZ(),
                tps[0], tps[1], tps[2],
                settings.testModeThreshold(), settings.blockModeThreshold());
    }

public boolean toggleOpAlerts(Player op) {
        UUID uuid = op.getUniqueId();
        if (alertsDisabled.contains(uuid)) { alertsDisabled.remove(uuid); return true; }
        alertsDisabled.add(uuid); return false;
    }

    public boolean hasAlertsEnabled(Player op) {
        return !alertsDisabled.contains(op.getUniqueId());
    }

public void removePlayer(UUID uuid) {
        flagDataMap.remove(uuid);
        testModeHitCount.remove(uuid);
    }

    public void clearAllFlags() {
        flagDataMap.clear();
        alertsDisabled.clear();
        testModeHitCount.clear();
    }

public void clearFlagCounters() {
        flagDataMap.clear();
        testModeHitCount.clear();
    }

public long getTotalHitsProcessed()  { return totalHitsProcessed.get(); }
    public long getTotalFlagsGenerated() { return totalFlagsGenerated.get(); }

    public List<Map.Entry<UUID, PlayerFlagData>> getTopSuspects(int limit) {
        List<Map.Entry<UUID, PlayerFlagData>> list = new ArrayList<>(flagDataMap.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue().getTotalFlags(), a.getValue().getTotalFlags()));
        return list.subList(0, Math.min(limit, list.size()));
    }

private void cleanupExpiredWindows() {
        long now = System.currentTimeMillis();
        ConfigManager.HotSettings settings = config.getHotSettingsSnapshot();
        for (Map.Entry<UUID, PlayerFlagData> entry : flagDataMap.entrySet()) {
            PlayerFlagData data = entry.getValue();
            synchronized (data) {
                data.resetWindowIfExpired(now, settings.flagResetTime());
            }
        }
    }

private String formatLocation(Player player) {
        var loc = player.getLocation();
        return String.format("%s x=%.1f y=%.1f z=%.1f",
                loc.getWorld() != null ? loc.getWorld().getName() : "?",
                loc.getX(), loc.getY(), loc.getZ());
    }
}
