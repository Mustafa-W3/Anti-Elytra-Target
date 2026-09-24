package com.antielytratarget.commands;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.managers.ConfigManager;
import com.antielytratarget.managers.FlagManager;
import com.antielytratarget.managers.ProfileManager;
import com.antielytratarget.models.FlagLogEntry;
import com.antielytratarget.utils.MessageUtil;
import com.antielytratarget.utils.PermissionUtil;
import com.antielytratarget.utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class AETCommand implements CommandExecutor, TabCompleter {

    private static final String DIV = "<dark_gray>----------------------------------------------";

    private final AntiElytraTargetPlugin plugin;
    private final ConfigManager config;
    private final FlagManager flagManager;
    private final ProfileManager profileManager;

    public AETCommand(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.flagManager = plugin.getFlagManager();
        this.profileManager = plugin.getProfileManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!PermissionUtil.isAdmin(sender) && !PermissionUtil.canWatchReplay(sender)) {
                noPermission(sender);
                return true;
            }
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "log" -> {
                if (requireAny(sender, PermissionUtil.LOG, PermissionUtil.NOTIFY, PermissionUtil.LEGACY_NOTIFY)) handleLog(sender);
            }
            case "profile" -> {
                if (require(sender, PermissionUtil.PROFILE)) handleProfile(sender, args, label);
            }
            case "reload" -> {
                if (require(sender, PermissionUtil.RELOAD)) handleReload(sender);
            }
            case "status" -> {
                if (require(sender, PermissionUtil.STATUS)) handleStatus(sender);
            }
            case "clear" -> {
                if (require(sender, PermissionUtil.CLEAR)) handleClear(sender, args, label);
            }
            case "suspect" -> {
                if (requireAny(sender, PermissionUtil.SUSPECT, PermissionUtil.LEGACY_SUSPECT)) handleSuspect(sender);
            }
            case "replay" -> {
                if (requireReplay(sender)) handleReplay(sender, args);
            }
            case "tpa" -> {
                if (require(sender, PermissionUtil.TPA)) handleTpa(sender, args);
            }
            case "language" -> handleLanguage(sender);
            case "help" -> sendHelp(sender, label);
            default -> MessageUtil.send(sender, config.getPrefix() + config.getMsgUnknownSub().replace("{label}", label));
        }
        return true;
    }

    private void handleLog(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgInGameOnly());
            return;
        }

        boolean on = flagManager.toggleOpAlerts(player);
        if (on) {
            MessageUtil.send(player, config.getPrefix() + config.getMsgLogEnabled());
            MessageUtil.send(player, config.getMsgLogEnabledSub());
        } else {
            MessageUtil.send(player, config.getPrefix() + config.getMsgLogDisabled());
            MessageUtil.send(player, config.getMsgLogDisabledSub());
        }
    }

    private void handleSuspect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgInGameOnly());
            return;
        }
        plugin.getSuspectGUI().open(player);
    }

    private void handleLanguage(CommandSender sender) {
        if (!PermissionUtil.isAdmin(sender)) {
            noPermission(sender);
            return;
        }
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgInGameOnly());
            return;
        }
        plugin.getLanguageGUI().open(player);
    }

    private void handleReplay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgInGameOnly());
            return;
        }
        if (args.length != 1) {
            MessageUtil.send(player, config.getPrefix() + m("replay_usage", "<red>Usage: /aet replay"));
            return;
        }
        var replayGUI = plugin.getReplayGUI();
        if (replayGUI == null) {
            MessageUtil.send(player, config.getPrefix() + config.getMsgReplayNotEnabled());
            return;
        }
        replayGUI.open(player);
    }

    private void handleTpa(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgInGameOnly());
            return;
        }
        if (args.length < 2) {
            MessageUtil.send(player, config.getPrefix() + m("tpa_usage", "<red>Usage: /aet tpa <player>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            MessageUtil.send(player, config.getPrefix() + m("player_not_found", "<yellow>Player not found: <white>{player}", "{player}", args[1]));
            return;
        }

        SchedulerUtil.runForEntity(plugin, target, () -> {
            if (!target.isOnline()) return;
            Location location = target.getLocation().clone();
            String targetName = target.getName();
            SchedulerUtil.runForEntity(plugin, player, () ->
                    SchedulerUtil.teleport(plugin, player, location, success -> {
                        if (!success) return;
                        try {
                            player.playSound(player.getLocation(),
                                    Sound.ENTITY_ENDERMAN_TELEPORT,
                                    1.0f, 1.0f);
                        } catch (Exception ignored) {}
                        MessageUtil.send(player, config.getPrefix()
                                + m("teleported", "<green>Teleported to <yellow>{player}<green>.",
                                "{player}", targetName));
                    }), null);
        }, null);
    }

    private void handleProfile(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgProfileUsage().replace("{label}", label));
            return;
        }

        String targetName = args[1];
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                MessageUtil.send(sender, config.getPrefix() + config.getMsgInvalidPage());
                return;
            }
        }

        Map.Entry<UUID, List<FlagLogEntry>> result = profileManager.findByName(targetName);
        if (result == null || result.getValue().isEmpty()) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgProfileNotFound().replace("{player}", targetName));
            return;
        }

        List<FlagLogEntry> entries = result.getValue();
        int total = entries.size();
        int entriesPerPage = 10;
        int totalPages = Math.max(1, (int) Math.ceil((double) total / entriesPerPage));

        if (page < 1 || page > totalPages) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgPageOutOfRange().replace("{max}", String.valueOf(totalPages)));
            return;
        }

        int startIdx = (page - 1) * entriesPerPage;
        int endIdx = Math.min(startIdx + entriesPerPage, total);

        MessageUtil.send(sender, DIV);
        MessageUtil.send(sender, m("profile_header_line", "<gold>  AntiElytraTarget <white>Profile: <yellow>{player}", "{player}", targetName));
        MessageUtil.send(sender, m("profile_uuid_line", "<gray>  UUID: <white>{uuid}", "{uuid}", result.getKey().toString()));
        MessageUtil.send(sender, m("profile_total_page_line", "<gray>  Total Flags: <red>{total} <gray>| Page: <white>{page}/{pages}",
                "{total}", String.valueOf(total), "{page}", String.valueOf(page), "{pages}", String.valueOf(totalPages)));
        MessageUtil.send(sender, DIV);

        Map<String, Long> counts = entries.stream()
                .collect(Collectors.groupingBy(FlagLogEntry::getCheckName, Collectors.counting()));
        MessageUtil.send(sender, m("profile_breakdown", "<aqua>  Flag Breakdown:"));
        counts.forEach((check, count) ->
                MessageUtil.send(sender, m("profile_breakdown_item", "<dark_gray>    - <yellow>{check}<gray>: <red>{count}",
                        "{check}", check, "{count}", String.valueOf(count))));

        MessageUtil.send(sender, DIV);
        MessageUtil.send(sender, m("profile_recent", "<aqua>  Recent Entries (newest first):"));

        List<FlagLogEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        List<FlagLogEntry> pageEntries = reversed.subList(startIdx, endIdx);
        for (int i = 0; i < pageEntries.size(); i++) {
            MessageUtil.send(sender, m("profile_entry", "<dark_gray>  [{index}] {entry}",
                    "{index}", String.valueOf(startIdx + i + 1), "{entry}", pageEntries.get(i).toDisplayLine()));
        }

        MessageUtil.send(sender, DIV);
        if (totalPages > 1) {
            MessageUtil.send(sender, m("profile_navigate", "<gray>  Use /aet profile {player} <page> to navigate.", "{player}", targetName));
        }
    }

    private void handleReload(CommandSender sender) {
        long start = System.currentTimeMillis();
        plugin.reload();
        long ms = System.currentTimeMillis() - start;
        MessageUtil.send(sender, config.getPrefix() + config.getMsgReloaded().replace("{ms}", String.valueOf(ms)));
    }

    private void handleStatus(CommandSender sender) {
        MessageUtil.send(sender, DIV);
        MessageUtil.send(sender, m("status_header", "<gold>  AntiElytraTarget <white>v{version} Status", "{version}", plugin.getDescription().getVersion()));
        MessageUtil.send(sender, DIV);
        status(sender, "status_plugin", "Plugin", boolS(config.isEnabled()));
        status(sender, "status_detection", "Detection", boolS(config.isDetectionEnabled()));
        status(sender, "status_offhand", "Offhand Firework", boolS(config.isBlockOffhandFirework()));
        status(sender, "status_mainhand_attack", "Mainhand Firework Attacks", boolS(config.isBlockMainhandFireworkAttacks()));
        status(sender, "status_debug", "Debug Mode", boolS(config.isDebugMode()));
        status(sender, "status_replay", "Replay", boolS(config.isReplayEnabled()));
        status(sender, "status_hits", "Hits processed", "<white>" + flagManager.getTotalHitsProcessed());
        status(sender, "status_flags", "Flags generated", "<red>" + flagManager.getTotalFlagsGenerated());
        status(sender, "status_tracked", "Tracked players", "<white>" + profileManager.getTrackedPlayerCount());
        status(sender, "status_online", "Online players", "<white>" + Bukkit.getOnlinePlayers().size());

        double[] tps = plugin.getCurrentTpsSamples();
        if (tps[0] > 0) {
            status(sender, "status_tps", "TPS (1/5/15m)",
                    fmtTps(tps[0]) + " <gray>/ " + fmtTps(tps[1]) + " <gray>/ " + fmtTps(tps[2]));
        } else {
            status(sender, "status_tps_short", "TPS", config.getMsgTpsNotAvailable());
        }
        MessageUtil.send(sender, DIV);
    }

    private void handleClear(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgClearUsage().replace("{label}", label));
            return;
        }

        String targetName = args[1];
        Map.Entry<UUID, List<FlagLogEntry>> result = profileManager.findByName(targetName);
        if (result == null) {
            MessageUtil.send(sender, config.getPrefix() + config.getMsgClearNoProfile().replace("{player}", targetName));
            return;
        }

        UUID uuid = result.getKey();
        profileManager.clearProfile(uuid);
        flagManager.removePlayer(uuid);
        MessageUtil.send(sender, config.getPrefix() + config.getMsgClearSuccess().replace("{player}", targetName));
        plugin.getLogger().info("[AET] " + sender.getName() + " cleared flag data for " + targetName + " (" + uuid + ")");
    }

    private void sendHelp(CommandSender sender, String label) {
        MessageUtil.send(sender, DIV);
        MessageUtil.send(sender, config.getMsgHelpHeader().replace("{label}", label));
        MessageUtil.send(sender, DIV);
        MessageUtil.send(sender, config.getMsgHelpLog().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpSuspect().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpProfile().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpProfilePage().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpClear().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpStatus().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpReload().replace("{label}", label));
        MessageUtil.send(sender, config.getMsgHelpReplay().replace("{label}", label));
        MessageUtil.send(sender, m("help_language", "<yellow>  /{label} language               <gray>Open the language selector", "{label}", label));
        MessageUtil.send(sender, config.getMsgHelpHelp().replace("{label}", label));
        MessageUtil.send(sender, DIV);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!PermissionUtil.isAdmin(sender) && !PermissionUtil.canWatchReplay(sender)) return Collections.emptyList();

        if (args.length == 1) {
            return filter(List.of("log", "profile", "clear", "status", "reload", "suspect", "replay", "tpa", "language", "help"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("profile") || args[0].equalsIgnoreCase("clear"))) {
            Set<String> names = new LinkedHashSet<>();
            Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(names::add);
            names.addAll(profileManager.getAllTrackedNames());
            return filter(new ArrayList<>(names), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tpa")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    private String boolS(boolean value) {
        return value ? m("yes", "<green>YES") : m("no", "<red>NO");
    }

    private void status(CommandSender sender, String key, String fallbackLabel, String value) {
        MessageUtil.send(sender, m(key, "<gray>  " + fallbackLabel + ": <reset>{value}", "{value}", value));
    }

    private String m(String key, String fallback, String... replacements) {
        String value = config.msg(key, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    private boolean require(CommandSender sender, String permission) {
        if (PermissionUtil.has(sender, permission)) return true;
        noPermission(sender);
        return false;
    }

    private boolean requireAny(CommandSender sender, String... permissions) {
        if (PermissionUtil.hasAny(sender, permissions)) return true;
        noPermission(sender);
        return false;
    }

    private boolean requireReplay(CommandSender sender) {
        if (PermissionUtil.canWatchReplay(sender)) return true;
        noPermission(sender);
        return false;
    }

    private void noPermission(CommandSender sender) {
        MessageUtil.send(sender, config.getPrefix() + config.getMsgNoPermission());
    }

    private String fmtTps(double tps) {
        tps = Math.min(tps, 20.0);
        if (tps >= 18.0) return "<green>" + String.format("%.1f", tps);
        if (tps >= 15.0) return "<yellow>" + String.format("%.1f", tps);
        return "<red>" + String.format("%.1f", tps);
    }
}
