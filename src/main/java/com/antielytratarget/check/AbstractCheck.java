package com.antielytratarget.check;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.BedrockPlayerUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

public abstract class AbstractCheck {

    protected final AntiElytraTargetPlugin plugin;
    protected final AETPlayer aetPlayer;

private final String checkName;
    private final String configName;
    private final String legacyConfigName;
    private final String description;

protected double violations = 0;
    protected double decay;
    protected boolean enabled = true;

    protected AbstractCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        this.plugin = plugin;
        this.aetPlayer = aetPlayer;

        CheckData data = this.getClass().getAnnotation(CheckData.class);
        if (data != null) {
            this.checkName = data.name();
            this.configName = data.name();
            this.legacyConfigName = data.configName().isEmpty() ? data.name() : data.configName();
            this.decay = data.decay();
            this.description = data.description();
        } else {
            this.checkName = getClass().getSimpleName();
            this.configName = checkName;
            this.legacyConfigName = checkName;
            this.decay = 0.05;
            this.description = "";
        }
    }

protected void flag(double addedVL) {
        violations += addedVL;
    }

protected boolean flagAndAlert(Player attacker, LivingEntity victim, double value) {
        if (!canCheck(attacker)) return false;
        flag(1.0);
        plugin.getFlagManager().processFlag(attacker, victim, checkName, value);
        return true;
    }

protected boolean flagAndAlert(double addedVL, Player attacker, LivingEntity victim, double value) {
        if (!canCheck(attacker)) return false;
        flag(addedVL);
        plugin.getFlagManager().processFlag(attacker, victim, checkName, value);
        return true;
    }

    protected boolean flagAndAlertNamedTarget(double addedVL, Player attacker,
                                              String victimName, double value) {
        if (!canCheck(attacker)) return false;
        flag(addedVL);
        plugin.getFlagManager().processFlag(attacker, victimName, checkName, value);
        return true;
    }

public void reward() {
        if (violations == 0.0) return;
        violations = Math.max(0, violations - decay);
    }

public void onReload() {
        String prefix = "checks." + configName + ".";
        String legacyPrefix = "checks." + legacyConfigName + ".";
        var cfg = plugin.getConfigManager().getConfig();
        this.enabled = cfg.contains(prefix + "enabled")
                ? cfg.getBoolean(prefix + "enabled", true)
                : cfg.getBoolean(legacyPrefix + "enabled", true);

        double cfgDecay = cfg.contains(prefix + "decay")
                ? cfg.getDouble(prefix + "decay", decay)
                : cfg.getDouble(legacyPrefix + "decay", decay);
        if (cfgDecay > 0) this.decay = cfgDecay;

        loadConfig();
    }

protected void loadConfig() {}

protected boolean canCheck(Player player) {
        if (player == null || !player.isOnline()) return false;
        if (!enabled) return false;
        if (plugin.isRuntimeSuspendedForTps()) return false;
        if (!plugin.getConfigManager().isDetectionEnabled()) return false;
        if (plugin.getConfigManager().isPingExempt(player)) return false;
        if (plugin.getConfigManager().isExemptCreative() && player.getGameMode() == GameMode.CREATIVE) return false;
        if (plugin.getConfigManager().isExemptSpectator() && player.getGameMode() == GameMode.SPECTATOR) return false;

        String bypass = plugin.getConfigManager().getBypassPermission();
        return !player.isPermissionSet(bypass) || !player.hasPermission(bypass);
    }

    protected boolean isBedrockExempt() {
        if (!plugin.getConfigManager().isBedrockSupportEnabled()) {
            return false;
        }

        return aetPlayer.isBedrockPlayer()
                || BedrockPlayerUtil.hasConfiguredNamePrefix(
                        aetPlayer.player.getName(),
                        plugin.getConfigManager().getBedrockNamePrefix());
    }

public String getCheckName() { return checkName; }
    public String getConfigName() { return configName; }
    public String getDescription() { return description; }
    public double getViolations() { return violations; }
    public boolean isEnabled() { return enabled; }

protected double cfg(String key, double def) {
        var cfg = plugin.getConfigManager().getConfig();
        String primary = "checks." + configName + "." + key;
        if (cfg.contains(primary)) return cfg.getDouble(primary, def);
        return cfg.getDouble("checks." + legacyConfigName + "." + key, def);
    }
    protected int cfgInt(String key, int def) {
        var cfg = plugin.getConfigManager().getConfig();
        String primary = "checks." + configName + "." + key;
        if (cfg.contains(primary)) return cfg.getInt(primary, def);
        return cfg.getInt("checks." + legacyConfigName + "." + key, def);
    }
    protected boolean cfgBool(String key, boolean def) {
        var cfg = plugin.getConfigManager().getConfig();
        String primary = "checks." + configName + "." + key;
        if (cfg.contains(primary)) return cfg.getBoolean(primary, def);
        return cfg.getBoolean("checks." + legacyConfigName + "." + key, def);
    }
    protected String cfgString(String key, String def) {
        var cfg = plugin.getConfigManager().getConfig();
        String primary = "checks." + configName + "." + key;
        if (cfg.contains(primary)) return cfg.getString(primary, def);
        return cfg.getString("checks." + legacyConfigName + "." + key, def);
    }
}
