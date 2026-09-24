package com.antielytratarget.managers;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class ConfigManager {

    public static final List<String> SUPPORTED_LANGUAGES =
            List.of("en", "tr", "it", "es", "fr", "de", "pt", "ru");

    public static final String DEFAULT_PREFIX =
            "<gray>&l[&x&F&F&0&0&0&0&lA&x&F&9&0&B&0&B&lE&x&F&3&1&5&1&5&lT<gray>&l] <reset>";

    private static final Set<String> ALERT_PLACEHOLDERS = Set.of(
            "player", "uuid", "ping", "gamemode", "world",
            "x", "y", "z", "check", "value", "flags", "window",
            "stage", "victim", "time", "location");

    private static final Pattern BRANDED_VERSION = Pattern.compile(
            "(?m)(?<prefix>^.*AntiElytraTarget.*?\\bv)\\d+(?:\\.\\d+){2,3}");

    private final AntiElytraTargetPlugin plugin;
    private volatile FileConfiguration   config;
    private volatile String              activeLanguage;
    private volatile HotSettings         hotSettings;

    public ConfigManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        ensureLanguageFiles();
        activeLanguage = loadSelectedLanguage();
        loadActiveConfig();
        plugin.getLogger().info("Configuration loaded: " + getActiveConfigName());
    }

    public void reload() {
        activeLanguage = loadSelectedLanguage();
        loadActiveConfig();
        plugin.getLogger().info("Configuration reloaded: " + getActiveConfigName());
    }

    private void ensureLanguageFiles() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create the plugin data folder.");
        }
        File configsFolder = new File(plugin.getDataFolder(), "configs");
        if (!configsFolder.exists() && !configsFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create the configs folder.");
        }

for (String code : SUPPORTED_LANGUAGES) {
            File oldLocation = new File(plugin.getDataFolder(), code + "_config.yml");
            File newLocation = new File(configsFolder, code + "_config.yml");
            if (oldLocation.exists() && !newLocation.exists()) {
                try {
                    Files.move(oldLocation.toPath(), newLocation.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    plugin.getLogger().info("Migrated " + oldLocation.getName() + " to configs/.");
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not migrate " + oldLocation.getName() + ": " + e.getMessage());
                }
            }
        }

        File legacy = new File(plugin.getDataFolder(), "config.yml");
        File english = new File(configsFolder, "en_config.yml");
        if (legacy.exists() && !english.exists()) {
            try {
                Files.move(legacy.toPath(), english.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Migrated config.yml to configs/en_config.yml.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not migrate config.yml to configs/en_config.yml: " + e.getMessage());
            }
        }

        for (String code : SUPPORTED_LANGUAGES) {
            String name = code + "_config.yml";
            File languageFile = new File(configsFolder, name);
            if (!languageFile.exists()) {
                plugin.saveResource("configs/" + name, false);
            }
            refreshVersionLabels(languageFile);
        }

        fillMissingLanguageSettings(configsFolder);
    }

    private void refreshVersionLabels(File file) {
        try {
            String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String replacement = "${prefix}" + plugin.getDescription().getVersion();
            String updated = BRANDED_VERSION.matcher(existing).replaceAll(replacement);
            if (!updated.equals(existing)) {
                Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "Could not refresh version labels in " + file.getName()
                            + ": " + e.getMessage());
        }
    }

private void fillMissingLanguageSettings(File configsFolder) {
        for (String code : SUPPORTED_LANGUAGES) {
            String name = code + "_config.yml";
            File file = new File(configsFolder, name);
            YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
            int added = 0;

            if (!existing.isSet("general.debugmod") && existing.isSet("general.debug")) {
                existing.set("general.debugmod",
                        existing.getBoolean("general.debug", false));
                existing.set("general.debug", null);
                added++;
            }

            if (existing.contains("checks.PacketOrder")) {
                existing.set("checks.PacketOrder", null);
                added++;
            }
            if (existing.contains("checks.packet_order")) {
                existing.set("checks.packet_order", null);
                added++;
            }
            if (existing.contains("checks.MultiActionOrder")) {
                existing.set("checks.MultiActionOrder", null);
                added++;
            }
            if (existing.contains("checks.multi_action_order")) {
                existing.set("checks.multi_action_order", null);
                added++;
            }

            String[] obsoletePacketReliabilitySettings = {
                    "checks.PacketOrderE.reliable_pong_ms",
                    "checks.PacketOrderE.max_pending_transactions",
                    "checks.packet_order_e.reliable_pong_ms",
                    "checks.packet_order_e.max_pending_transactions"
            };
            for (String path : obsoletePacketReliabilitySettings) {
                if (existing.contains(path)) {
                    existing.set(path, null);
                    added++;
                }
            }

            String globalBedrockPrefix = "compatibility.bedrock_name_prefix";
            if (!existing.isSet(globalBedrockPrefix)) {
                String legacyPrefix = existing.getString("checks.PacketOrderE.bedrock_name_prefix");
                if (legacyPrefix == null) {
                    legacyPrefix = existing.getString("checks.packet_order_e.bedrock_name_prefix");
                }
                if (legacyPrefix != null) {
                    existing.set(globalBedrockPrefix, legacyPrefix);
                    added++;
                }
            }
            if (existing.contains("checks.PacketOrderE.exempt_bedrock")) {
                existing.set("checks.PacketOrderE.exempt_bedrock", null);
                added++;
            }
            if (existing.contains("checks.PacketOrderE.bedrock_name_prefix")) {
                existing.set("checks.PacketOrderE.bedrock_name_prefix", null);
                added++;
            }
            if (existing.contains("checks.packet_order_e.exempt_bedrock")) {
                existing.set("checks.packet_order_e.exempt_bedrock", null);
                added++;
            }
            if (existing.contains("checks.packet_order_e.bedrock_name_prefix")) {
                existing.set("checks.packet_order_e.bedrock_name_prefix", null);
                added++;
            }

            try (InputStream stream = plugin.getResource("configs/" + name)) {
                if (stream == null) continue;
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                for (String path : bundled.getKeys(true)) {
                    if (bundled.isConfigurationSection(path) || existing.contains(path)) continue;
                    existing.set(path, bundled.get(path));
                    added++;
                }
                if (added > 0) {
                    existing.save(file);
                    plugin.getLogger().info("Updated " + name + " with " + added + " configuration change(s).");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Could not update " + name + ": " + e.getMessage());
            }
        }
    }

    private String loadSelectedLanguage() {
        File file = new File(plugin.getDataFolder(), "language.yml");
        if (!file.exists()) return "en";
        String selected = YamlConfiguration.loadConfiguration(file)
                .getString("language", "en").toLowerCase(Locale.ROOT);
        return SUPPORTED_LANGUAGES.contains(selected) ? selected : "en";
    }

    private void loadActiveConfig() {
        File configsFolder = new File(plugin.getDataFolder(), "configs");
        File selectedFile = new File(configsFolder, activeLanguage + "_config.yml");
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(selectedFile);
        repairDisabledActiveConfig(selectedFile, loaded);
        this.config = loaded;
        MessageUtil.clearComponentCache();
        cacheHotSettings();
    }

    private void cacheHotSettings() {
        String alertMessage = raw("notifications.alert_message",
                DEFAULT_PREFIX + "<yellow>{player} <dark_gray>| <red>{check} <dark_gray>| <gray>{flags}x <dark_gray>| <gray>{location}");
        String alertHover = raw("notifications.alert_hover",
                "<gray>Username: <white>{player}\\n"
              + "<gray>Ping: <green>{ping}ms\\n"
              + "<gray>Gamemode: <aqua>{gamemode}\\n"
              + "<gray>Position: <white>{world} <yellow>{x}<gray>, <yellow>{y}<gray>, <yellow>{z}\\n"
              + "<gray>Check: <red>{check}\\n"
              + "<gray>Total Flags: <red>{flags}\\n"
              + "<gray>Window Flags: <yellow>{window}\\n"
              + "<gray>Victim: <white>{victim}\\n"
              + "\\n<green>Click to TPA to <yellow>{player}");
        MessageUtil.CompiledTemplate alertMessageTemplate =
                MessageUtil.compileTemplate(alertMessage, ALERT_PLACEHOLDERS);
        MessageUtil.CompiledTemplate alertHoverTemplate =
                MessageUtil.compileTemplate(alertHover.replace("\\n", "\n"), ALERT_PLACEHOLDERS);
        String bedrockNamePrefix = raw("compatibility.bedrock_name_prefix", ".");
        bedrockNamePrefix = bedrockNamePrefix == null ? "" : bedrockNamePrefix.trim();

        hotSettings = new HotSettings(
                config.getBoolean("general.enabled", true),
                raw("general.prefix", DEFAULT_PREFIX),
                config.getBoolean("general.debugmod",
                        config.getBoolean("general.debug", true)),
                config.getBoolean("detection.enabled", true),
                config.getBoolean("detection.block_offhand_firework", false),
                config.getBoolean("detection.block_mainhand_firework_attacks", false),
                config.getBoolean("compatibility.bedrock_support", false),
                bedrockNamePrefix,
                config.getBoolean("compatibility.synthetic_transactions", true),
                Math.max(1, config.getInt(
                        "compatibility.synthetic_transaction_interval_ticks", 5)),
                config.getDouble("performance.disable_below_tps", 16.0),
                config.getLong("detection.flag_reset_time", 20) * 1000L,
                config.getInt("penalty.test_mode_threshold", 2),
                config.getInt("penalty.block_mode_threshold", 5),
                config.getDouble("penalty.test_mode_block_ratio", 0.5),
                config.getBoolean("penalty.kick_enabled", true),
                config.getInt("penalty.kick_threshold", 20),
                config.getBoolean("penalty.kick_command_enabled", false),
                raw("penalty.kick_command", "kick {player} ElytraTarget detected"),
                MessageUtil.legacyColorize(raw(
                        "penalty.kick_message",
                        "&cKicked: ElytraTarget hack detected.")),
                config.getBoolean("penalty.ban_enabled", false),
                config.getInt("penalty.ban_threshold", 60),
                config.getBoolean("penalty.ban_command_enabled", false),
                raw("penalty.ban_command",
                        "tempban {player} 1d ElytraTarget detected"),
                MessageUtil.legacyColorize(raw(
                        "penalty.ban_message",
                        "&cBanned: ElytraTarget hacking.")),
                config.getLong("penalty.ban_duration_minutes", 1440),
                config.getBoolean("logging.file_logging", true),
                config.getBoolean("logging.console_logging", true),
                config.getInt("exemptions.max_ping", 100),
                config.getBoolean("exemptions.exempt_creative", true),
                config.getBoolean("exemptions.exempt_spectator", true),
                raw("exemptions.bypass_permission", "antielytratarget.bypass"),
                alertMessage,
                alertHover,
                alertMessageTemplate,
                alertHoverTemplate,
                config.getBoolean("notifications.alert_click_enabled", true),
                raw("notifications.alert_click_action", "run_command"),
                raw("notifications.alert_click_command", "/aet tpa {player}"));
    }

    private void repairDisabledActiveConfig(File selectedFile, YamlConfiguration loaded) {
        if (!loaded.isSet("general.enabled") || loaded.getBoolean("general.enabled", true)) return;

        loaded.set("general.enabled", true);
        try {
            loaded.save(selectedFile);
            plugin.getLogger().warning("Re-enabled general.enabled in " + getActiveConfigName()
                    + " for legacy config migration. Use detection.enabled=false to pause checks.");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not persist general.enabled=true in "
                    + getActiveConfigName() + ": " + e.getMessage());
        }
    }

    public boolean setLanguage(String code) {
        String normalized = code == null ? "" : code.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) return false;
        activeLanguage = normalized;
        YamlConfiguration state = new YamlConfiguration();
        state.set("language", activeLanguage);
        try {
            state.save(new File(plugin.getDataFolder(), "language.yml"));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save language.yml: " + e.getMessage());
            return false;
        }
        loadActiveConfig();
        return true;
    }

    public FileConfiguration getConfig() { return config; }
    public HotSettings getHotSettingsSnapshot() { return hotSettings; }
    public String getActiveLanguage() { return activeLanguage; }
    public String getActiveConfigName() { return "configs/" + activeLanguage + "_config.yml"; }

public boolean isEnabled()   { return hotSettings.enabled(); }
    public String  getPrefix()   { return hotSettings.prefix(); }
    public boolean isDebugMode() { return hotSettings.debugMode(); }

public boolean isDetectionEnabled() { return hotSettings.detectionEnabled(); }
    public boolean isBlockOffhandFirework() { return hotSettings.blockOffhandFirework(); }
    public boolean isBlockMainhandFireworkAttacks() { return hotSettings.blockMainhandFireworkAttacks(); }
    public boolean isBedrockSupportEnabled() { return hotSettings.bedrockSupportEnabled(); }
    public String getBedrockNamePrefix() { return hotSettings.bedrockNamePrefix(); }

    public boolean isSyntheticTransactionsEnabled() {
        return hotSettings.syntheticTransactionsEnabled();
    }

    public int getSyntheticTransactionIntervalTicks() {
        return hotSettings.syntheticTransactionIntervalTicks();
    }

    public double getTpsDisableThreshold() {
        return hotSettings.tpsDisableThreshold();
    }

public long    getFlagResetTime()           { return hotSettings.flagResetTime(); }

    public int     getTestModeThreshold()  { return hotSettings.testModeThreshold(); }
    public int     getBlockModeThreshold() { return hotSettings.blockModeThreshold(); }
    public double  getTestModeBlockRatio() { return hotSettings.testModeBlockRatio(); }
    public boolean isKickEnabled()         { return hotSettings.kickEnabled(); }
    public int     getKickThreshold()      { return hotSettings.kickThreshold(); }
    public String  getKickMessage()        { return hotSettings.kickMessage(); }
    public boolean isKickCommandEnabled()  { return hotSettings.kickCommandEnabled(); }
    public String  getKickCommand()        { return hotSettings.kickCommand(); }
    public boolean isBanEnabled()          { return hotSettings.banEnabled(); }
    public int     getBanThreshold()       { return hotSettings.banThreshold(); }
    public String  getBanMessage()         { return hotSettings.banMessage(); }
    public long    getBanDurationMinutes() { return hotSettings.banDurationMinutes(); }
    public boolean isBanCommandEnabled()   { return hotSettings.banCommandEnabled(); }
    public String  getBanCommand()         { return hotSettings.banCommand(); }

public boolean isFileLoggingEnabled()    { return hotSettings.fileLoggingEnabled(); }
    public String  getLogFilePath()          { return config.getString("logging.log_file_path", "logs/flags.log"); }
    public boolean isConsoleLoggingEnabled() { return hotSettings.consoleLoggingEnabled(); }
    public boolean isOpAlertsDefault()       { return config.getBoolean("logging.op_alerts_default", true); }
    public boolean isLogLocation()           { return config.getBoolean("logging.log_location", true); }
    public boolean isLogVictimInfo()         { return config.getBoolean("logging.log_victim_info", true); }
    public int     getMaxProfileEntries()    { return config.getInt("logging.max_profile_entries", 200); }

public boolean isExemptCreative()    { return hotSettings.exemptCreative(); }
    public boolean isExemptSpectator()   { return hotSettings.exemptSpectator(); }
    public int getMaxPing()               { return hotSettings.maxPing(); }
    public boolean isPingExempt(Player player) {
        if (player == null || hotSettings.maxPing() < 0) return false;
        try {
            return player.getPing() > hotSettings.maxPing();
        } catch (Throwable ignored) {
            return false;
        }
    }
    public String  getBypassPermission() { return hotSettings.bypassPermission(); }

public boolean isReplayEnabled()       { return config.getBoolean("replay.enabled", false); }
    public int     getReplayFlagThreshold(){ return 2; }
    public int     getReplayDuration()     { return config.getInt("replay.duration_seconds", 45); }
    public int     getReplayPreRecordSeconds() { return 0; }
    public boolean isReplayPreRecordEntities() { return false; }
    public double  getReplayEntityRadius() { return config.getDouble("replay.entity_radius", 18.0); }
    public int     getReplayMaxActorsPerFrame() { return config.getInt("replay.max_actors_per_frame", 24); }
    public int     getReplayMaxEffectsPerFrame() { return config.getInt("replay.max_effects_per_frame", 128); }
    public int     getReplayTargetKeepSeconds() { return config.getInt("replay.target_keep_seconds", 8); }
    public int     getReplayMaxFiles()     { return config.getInt("replay.max_files", 100); }
    public double  getReplayIsolationRadius() { return config.getDouble("replay.isolation_radius", 96.0); }

public String getMsgHelpReplay()       { return msg("help_replay", "<yellow>  /{label} replay                 <gray>Open saved replay menu"); }
    public String getMsgReplayNotEnabled() { return msg("replay_not_enabled", "<red>Replay system is not enabled."); }
    public String getMsgReplayNoFiles()    { return msg("replay_no_files", "<yellow>No saved replays found."); }
    public String getMsgReplayStarted()    { return msg("replay_started", "<green>Recording started for <yellow>{player}<green>."); }
    public String getMsgReplaySaved()      { return msg("replay_saved", "<green>Replay saved: <yellow>{file}"); }
    public String getMsgReplayStopCmd()    { return msg("replay_stop", "<yellow>Replay playback stopped."); }

public String getAlertMessage() {
        return hotSettings.alertMessage();
    }

public String getAlertHover() {
        return hotSettings.alertHover();
    }

    public MessageUtil.CompiledTemplate getAlertMessageTemplate() {
        return hotSettings.alertMessageTemplate();
    }

    public MessageUtil.CompiledTemplate getAlertHoverTemplate() {
        return hotSettings.alertHoverTemplate();
    }

    public boolean isAlertClickEnabled() { return hotSettings.alertClickEnabled(); }
    public String getAlertClickAction()  { return hotSettings.alertClickAction(); }
    public String getAlertClickCommand() { return hotSettings.alertClickCommand(); }

public String msg(String key, String defaultValue) {
        return config.getString("messages." + key, defaultValue);
    }

public String raw(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }

public String getMsgNoPermission()      { return msg("no_permission",      "<red>You do not have permission to use this command."); }
    public String getMsgInGameOnly()        { return msg("in_game_only",       "<red>This command can only be used in-game."); }
    public String getMsgUnknownSub()        { return msg("unknown_subcommand", "<red>Unknown subcommand. Use /{label} help"); }
    public String getMsgInvalidPage()       { return msg("invalid_page",       "<red>Invalid page number."); }
    public String getMsgPageOutOfRange()    { return msg("page_out_of_range",  "<red>Page out of range. Valid: 1-{max}"); }

public String getMsgLogEnabled()        { return msg("log_enabled",     "<green>Flag alerts <white>ENABLED<green>."); }
    public String getMsgLogEnabledSub()     { return msg("log_enabled_sub", "<gray>You will receive in-game notifications for every flag."); }
    public String getMsgLogDisabled()       { return msg("log_disabled",    "<red>Flag alerts <white>DISABLED<red>."); }
    public String getMsgLogDisabledSub()    { return msg("log_disabled_sub","<gray>Flags will still be sent to file and console."); }

public String getMsgReloaded()          { return msg("reloaded", "<green>Configuration reloaded in {ms} ms."); }

public String getMsgClearUsage()        { return msg("clear_usage",      "<red>Usage: /{label} clear <player>"); }
    public String getMsgClearNoProfile()    { return msg("clear_no_profile", "<red>No profile found for: {player}"); }
    public String getMsgClearSuccess()      { return msg("clear_success",    "<green>Cleared all flag data for <yellow>{player} <green>(profile + counters)."); }

public String getMsgProfileUsage()      { return msg("profile_usage",     "<red>Usage: /{label} profile <player> [page]"); }
    public String getMsgProfileNotFound()   { return msg("profile_not_found", "<yellow>No flag history found for <white>{player}<yellow>."); }

public String getMsgTpsNotAvailable()   { return msg("tps_not_available", "<yellow>N/A (not Paper)"); }

public String getMsgHelpHeader()        { return msg("help_header",       "<gold>  AntiElytraTarget <white>v5.3.1.3 Commands  <dark_gray>(alias: /aet)"); }
    public String getMsgHelpLog()           { return msg("help_log",          "<yellow>  /{label} log                    <gray>Toggle your in-game flag alerts"); }
    public String getMsgHelpSuspect()       { return msg("help_suspect",      "<yellow>  /{label} suspect                <gray>Open the suspect leaderboard GUI"); }
    public String getMsgHelpProfile()       { return msg("help_profile",      "<yellow>  /{label} profile <player>       <gray>View flag history (paginated)"); }
    public String getMsgHelpProfilePage()   { return msg("help_profile_page", "<yellow>  /{label} profile <player> <p>   <gray>Navigate pages"); }
    public String getMsgHelpClear()         { return msg("help_clear",        "<yellow>  /{label} clear <player>         <gray>Clear profile + runtime counters"); }
    public String getMsgHelpStatus()        { return msg("help_status",       "<yellow>  /{label} status                 <gray>Plugin status + live statistics"); }
    public String getMsgHelpReload()        { return msg("help_reload",       "<yellow>  /{label} reload                 <gray>Hot-reload the active language config"); }
    public String getMsgHelpHelp()          { return msg("help_help",         "<yellow>  /{label} help                   <gray>Show this help menu"); }

public String getMsgNotifyKick()        { return msg("notify_kick", "<red>KICK <dark_gray>| <yellow>{player} <gray>— ElytraTarget (flags: <red>{flags}<gray>)"); }
    public String getMsgNotifyBan()         { return msg("notify_ban",  "<red>BAN  <dark_gray>| <yellow>{player} <gray>— ElytraTarget (flags: <red>{flags}<gray>)"); }

private String gui(String key, String def) { return config.getString("gui.suspect." + key, def); }

    public String  getGuiTitle()                 { return gui("title",                    DEFAULT_PREFIX + "<yellow>Top Suspects"); }
    public int     getGuiSize()                  { return config.getInt("gui.suspect.size", 54); }

public String  getGuiHeadName()              { return gui("head_name",   "<gold>{player}"); }
    public String  getGuiLoreTotal()             { return gui("lore_total",  "<gray>Total Flags  : <red>{total}"); }
    public String  getGuiLoreWindow()            { return gui("lore_window", "<gray>Window Flags : <yellow>{window}"); }
    public String  getGuiLoreStatus()            { return gui("lore_status", "<gray>Status       : {stage}"); }
    public String  getGuiLoreClick()             { return gui("lore_click",  "<dark_gray>Click for detailed profile"); }

public String  getGuiStageWatching()         { return gui("stage_watching",  "<aqua>👁 WATCHING"); }
    public String  getGuiStageTesting()          { return gui("stage_testing",   "<yellow>⚠ TESTING"); }
    public String  getGuiStageBlocking()         { return gui("stage_blocking",  "<red>⛔ BLOCKING"); }

public String  getGuiDivider()               { return gui("divider",                  "<dark_gray>──────────────────────────────────────────────"); }
    public String  getGuiProfileHeader()         { return gui("profile_header",            "<gold>  AntiElytraTarget <white>Profile: <yellow>{player}"); }
    public String  getGuiProfileUuid()           { return gui("profile_uuid",              "<gray>  UUID: <white>{uuid}"); }
    public String  getGuiProfileTotal()          { return gui("profile_total",             "<gray>  Total Flags: <red>{total}"); }
    public String  getGuiProfileBreakdownHeader(){ return gui("profile_breakdown_header",  "<aqua>  Flag Breakdown:"); }
    public String  getGuiProfileBreakdownLine()  { return gui("profile_breakdown_line",    "<dark_gray>    • <yellow>{check}<gray>: <red>{count}"); }
    public String  getGuiProfileRecentHeader()   { return gui("profile_recent_header",     "<aqua>  Last 10 entries (newest first):"); }
    public String  getGuiProfileRecentLine()     { return gui("profile_recent_line",       "<dark_gray>  [{index}] {entry}"); }
    public String  getGuiProfileFooter()         { return gui("profile_footer",            "<gray>  Use <yellow>/aet profile {player} [page]<gray> for full paginated history."); }
    public String  getGuiNoProfile()             { return gui("no_profile",                "<yellow>No persistent flag history for <white>{player}<yellow>. Check /aet status for live data."); }

    public record HotSettings(
            boolean enabled,
            String prefix,
            boolean debugMode,
            boolean detectionEnabled,
            boolean blockOffhandFirework,
            boolean blockMainhandFireworkAttacks,
            boolean bedrockSupportEnabled,
            String bedrockNamePrefix,
            boolean syntheticTransactionsEnabled,
            int syntheticTransactionIntervalTicks,
            double tpsDisableThreshold,
            long flagResetTime,
            int testModeThreshold,
            int blockModeThreshold,
            double testModeBlockRatio,
            boolean kickEnabled,
            int kickThreshold,
            boolean kickCommandEnabled,
            String kickCommand,
            String kickMessage,
            boolean banEnabled,
            int banThreshold,
            boolean banCommandEnabled,
            String banCommand,
            String banMessage,
            long banDurationMinutes,
            boolean fileLoggingEnabled,
            boolean consoleLoggingEnabled,
            int maxPing,
            boolean exemptCreative,
            boolean exemptSpectator,
            String bypassPermission,
            String alertMessage,
            String alertHover,
            MessageUtil.CompiledTemplate alertMessageTemplate,
            MessageUtil.CompiledTemplate alertHoverTemplate,
            boolean alertClickEnabled,
            String alertClickAction,
            String alertClickCommand) {
    }
}
