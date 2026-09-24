package com.antielytratarget;

import com.antielytratarget.check.CheckManager;
import com.antielytratarget.commands.AETCommand;
import com.antielytratarget.gui.SuspectGUI;
import com.antielytratarget.gui.LanguageGUI;
import com.antielytratarget.listeners.CombatListener;
import com.antielytratarget.listeners.InteractListener;
import com.antielytratarget.listeners.MoveListener;
import com.antielytratarget.listeners.PaperIncapableSwapListener;
import com.antielytratarget.managers.ConfigManager;
import com.antielytratarget.managers.FlagManager;
import com.antielytratarget.managers.LogManager;
import com.antielytratarget.managers.ProfileManager;
import com.antielytratarget.netty.AETPacketEventsManager;
import com.antielytratarget.netty.NettyManager;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.player.PlayerTracker;
import com.antielytratarget.replay.ReplayGUI;
import com.antielytratarget.replay.ReplayPlayer;
import com.antielytratarget.replay.ReplayRecorder;
import com.antielytratarget.utils.BedrockPlayerUtil;
import com.antielytratarget.utils.SchedulerUtil;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class AntiElytraTargetPlugin extends JavaPlugin {

    private static AntiElytraTargetPlugin instance;
    private static final long TPS_CACHE_TTL_MILLIS = 5000L;

    private ConfigManager   configManager;
    private FlagManager     flagManager;
    private LogManager      logManager;
    private ProfileManager  profileManager;
    private SuspectGUI      suspectGUI;
    private LanguageGUI     languageGUI;
    private NettyManager       nettyManager;
    private PlayerTracker      playerTracker;
    private volatile ReplayRecorder replayRecorder;
    private volatile ReplayPlayer replayPlayer;
    private volatile ReplayGUI replayGUI;
    private AETPacketEventsManager packetEventsManager;

private CombatListener   combatListener;
    private MoveListener     moveListener;
    private InteractListener interactListener;
    private Listener paperIncapableSwapListener;

private SchedulerUtil.TaskWrapper decayTask;
    private final Map<UUID, PlayerTickTask> foliaPlayerTickTasks =
            new ConcurrentHashMap<>();
    private boolean tpsSuspended;
    private double[] cachedTpsSamples = {-1.0, -1.0, -1.0};
    private long cachedTpsAtMillis;

    @Override
    public void onLoad() {
        instance = this;
        packetEventsManager = new AETPacketEventsManager(this);
        packetEventsManager.load();
    }

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("  ");
        getLogger().info("  Anti Elytra Target v" + getDescription().getVersion());
        getLogger().info("  ");

configManager  = new ConfigManager(this);
        if (configManager.isBedrockSupportEnabled()) {
            BedrockPlayerUtil.initialize();
        }
        logManager     = new LogManager(this);
        flagManager    = new FlagManager(this);
        profileManager = new ProfileManager(this);
        suspectGUI     = new SuspectGUI(this);
        languageGUI    = new LanguageGUI(this);
        playerTracker  = new PlayerTracker();

        registerCommand();
        getServer().getPluginManager().registerEvents(suspectGUI, this);
        getServer().getPluginManager().registerEvents(languageGUI, this);

        if (!configManager.isEnabled()) {
            getLogger().warning("Plugin disabled in " + configManager.getActiveConfigName()
                    + " - no detection listeners registered.");
            return;
        }

        if (packetEventsManager == null) {
            packetEventsManager = new AETPacketEventsManager(this);
            packetEventsManager.load();
        }
        packetEventsManager.start();

        nettyManager = new NettyManager(this);
        getServer().getPluginManager().registerEvents(nettyManager, this);
        nettyManager.injectOnlinePlayers();

        registerListeners();

        reconfigureReplaySystem();

for (Player p : Bukkit.getOnlinePlayers()) {
            initPlayerChecks(p);
        }

        if (!SchedulerUtil.isFolia()) {
            decayTask = SchedulerUtil.runTaskTimer(this, () -> {
                for (AETPlayer aet : playerTracker.getAll().values()) {
                    if (aet.checkManager != null) {
                        aet.checkManager.tickReward();
                    }
                }
            }, 20L, 1L);
        }

        getLogger().info("  Detection  : " + (configManager.isDetectionEnabled() ? "ON" : "OFF")
                + "   |   Debug : " + (configManager.isDebugMode() ? "ON" : "OFF"));
        getLogger().info("  Kick       : " + (configManager.isKickEnabled()
                ? "ON (threshold=" + configManager.getKickThreshold() + ")" : "OFF"));
        getLogger().info("  Bypass     : " + configManager.getBypassPermission());
        getLogger().info("  TPS Guard  : " + (configManager.getTpsDisableThreshold() > 0
                ? "ON (<" + configManager.getTpsDisableThreshold() + ")" : "OFF"));
        getLogger().info("  Scheduler  : " + (SchedulerUtil.isFolia() ? "Folia (regionized)" : "Bukkit (standard)"));
        getLogger().info("  Architecture: v5.0 Replay NPC Engine");
        getLogger().info("  ");
    }

    @Override
    public void onDisable() {
        if (decayTask != null) decayTask.cancel();
        stopFoliaPlayerTickTasks();
        disableReplaySystem();
        if (logManager     != null) logManager.close();
        if (profileManager != null) profileManager.saveAllProfiles();
        if (nettyManager   != null) nettyManager.shutdown();
        if (packetEventsManager != null) packetEventsManager.stop();
        if (playerTracker  != null) playerTracker.clear();

        getLogger().info("Anti Elytra Target disabled.");
        instance = null;
    }

    public void reload() {
        reloadConfiguration(true);
    }

    public void reloadLanguage() {
        reloadConfiguration(false);
    }

    private void reloadConfiguration(boolean clearRuntimeCounters) {
        if (logManager != null) logManager.close();
        configManager.reload();
        if (configManager.isBedrockSupportEnabled()) {
            BedrockPlayerUtil.initialize();
        }
        logManager = new LogManager(this);
        if (clearRuntimeCounters) flagManager.clearFlagCounters();
        reconfigureReplaySystem();

if (combatListener != null) HandlerList.unregisterAll(combatListener);
        if (moveListener != null) HandlerList.unregisterAll(moveListener);
        if (interactListener != null) HandlerList.unregisterAll(interactListener);
        if (paperIncapableSwapListener != null) {
            HandlerList.unregisterAll(paperIncapableSwapListener);
            paperIncapableSwapListener = null;
        }

        registerListeners();

for (Player p : Bukkit.getOnlinePlayers()) {
            initPlayerChecks(p);
        }

        getLogger().info("[AET] " + configManager.getActiveConfigName() + " loaded and listeners re-registered.");
    }

    private synchronized void reconfigureReplaySystem() {
        boolean shouldRun = configManager != null && configManager.isReplayEnabled()
                && configManager.isEnabled();
        if (!shouldRun) {
            disableReplaySystem();
            getLogger().info("  Replay     : OFF (no listener, task or cache allocated)");
            return;
        }
        if (replayRecorder != null) return;

        ReplayRecorder recorder = new ReplayRecorder(this);
        ReplayPlayer player = new ReplayPlayer(this);
        ReplayGUI gui = new ReplayGUI(this);
        replayRecorder = recorder;
        replayPlayer = player;
        replayGUI = gui;
        getServer().getPluginManager().registerEvents(recorder, this);
        getServer().getPluginManager().registerEvents(player, this);
        getServer().getPluginManager().registerEvents(gui, this);
        recorder.start();
        getLogger().info("  Replay     : ON (starts at flag 2, duration="
                + configManager.getReplayDuration() + "s, no pre-buffer)");
    }

    private synchronized void disableReplaySystem() {
        ReplayRecorder recorder = replayRecorder;
        ReplayPlayer player = replayPlayer;
        ReplayGUI gui = replayGUI;

        replayRecorder = null;
        replayPlayer = null;
        replayGUI = null;

        if (recorder != null) recorder.stop();
        if (player != null) player.stopAll();
        if (gui != null) gui.shutdown();
        if (recorder != null) HandlerList.unregisterAll(recorder);
        if (player != null) HandlerList.unregisterAll(player);
        if (gui != null) HandlerList.unregisterAll(gui);
        if (recorder != null || player != null || gui != null) {
            ReplayRecorder.clearStaticCaches();
        }
    }

public void initPlayerChecks(Player player) {
        AETPlayer aet = playerTracker.get(player);
        aet.checkManager = new CheckManager(this, aet);
        if (SchedulerUtil.isFolia()) {
            ensureFoliaPlayerTickTask(player, aet);
        }
    }

    public boolean removePlayerChecks(Player player) {
        UUID uuid = player.getUniqueId();
        foliaPlayerTickTasks.computeIfPresent(uuid, (key, current) -> {
            if (current.player() != player) return current;
            current.task().cancel();
            return null;
        });
        return playerTracker.remove(player);
    }

    private void ensureFoliaPlayerTickTask(Player player, AETPlayer aet) {
        UUID uuid = player.getUniqueId();
        foliaPlayerTickTasks.compute(uuid, (key, existing) -> {
            if (existing != null && existing.player() == player) {
                return existing;
            }
            if (existing != null) existing.task().cancel();

            SchedulerUtil.TaskWrapper task = SchedulerUtil.runForEntityTimer(
                    this, player, () -> {
                        AETPlayer current = playerTracker.get(uuid);
                        if (current == aet && current.checkManager != null) {
                            current.checkManager.tickReward();
                        }
                    }, () -> {}, 20L, 1L);
            return new PlayerTickTask(player, task);
        });
    }

    private void stopFoliaPlayerTickTasks() {
        for (PlayerTickTask task : foliaPlayerTickTasks.values()) {
            task.task().cancel();
        }
        foliaPlayerTickTasks.clear();
    }

    private record PlayerTickTask(Player player,
                                  SchedulerUtil.TaskWrapper task) {}

    private void registerListeners() {
        combatListener   = new CombatListener(this);
        moveListener     = new MoveListener(this);
        interactListener = new InteractListener(this);

        getServer().getPluginManager().registerEvents(combatListener, this);
        getServer().getPluginManager().registerEvents(moveListener, this);
        getServer().getPluginManager().registerEvents(interactListener, this);
        registerPaperIncapableSwapListener();
    }

    private void registerPaperIncapableSwapListener() {
        try {
            ClassLoader loader = getClass().getClassLoader();
            Class.forName(
                    "com.destroystokyo.paper.event.player.PlayerElytraBoostEvent",
                    false, loader);
            Class.forName(
                    "com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent",
                    false, loader);

            paperIncapableSwapListener =
                    new PaperIncapableSwapListener(this);
            getServer().getPluginManager().registerEvents(
                    paperIncapableSwapListener, this);
            debug("[IncapableSwap] Exact Paper/Leaf boost and cooldown events active.");
        } catch (ClassNotFoundException | LinkageError unavailable) {
            paperIncapableSwapListener = null;
            debug("[IncapableSwap] Exact Paper events unavailable; check stays fail-closed.");
        }
    }

    private void registerCommand() {
        var cmd = getCommand("antielytratarget");
        if (cmd != null) {
            AETCommand aetCmd = new AETCommand(this);
            cmd.setExecutor(aetCmd);
            cmd.setTabCompleter(aetCmd);
        } else {
            getLogger().severe("Failed to register /antielytratarget - check plugin.yml!");
        }

    }

    public static AntiElytraTargetPlugin getInstance() { return instance; }

    public ConfigManager   getConfigManager()   { return configManager; }
    public FlagManager     getFlagManager()     { return flagManager; }
    public LogManager      getLogManager()      { return logManager; }
    public ProfileManager  getProfileManager()  { return profileManager; }
    public SuspectGUI      getSuspectGUI()      { return suspectGUI; }
    public LanguageGUI     getLanguageGUI()     { return languageGUI; }
    public NettyManager       getNettyManager()       { return nettyManager; }
    public PlayerTracker      getPlayerTracker()      { return playerTracker; }
    public ReplayRecorder     getReplayRecorder()     { return replayRecorder; }
    public ReplayPlayer       getReplayPlayer()       { return replayPlayer; }
    public ReplayGUI          getReplayGUI()          { return replayGUI; }
    public AETPacketEventsManager getPacketEventsManager() { return packetEventsManager; }

    public void debug(String msg) {
        if (isDebugEnabled()) {
            getLogger().log(Level.INFO, "[DEBUG] " + msg);
        }
    }

    public boolean isDebugEnabled() {
        return configManager != null && configManager.isDebugMode();
    }

    public boolean isRuntimeSuspendedForTps() {
        if (configManager == null) return false;
        double threshold = configManager.getTpsDisableThreshold();
        if (threshold <= 0) return false;

        double tps = getCurrentTps();
        if (tps <= 0) return false;

        boolean suspend = tps < threshold;
        if (suspend != tpsSuspended) {
            tpsSuspended = suspend;
            if (suspend) {
                getLogger().warning("[AET] Runtime guard paused detection/replay work because TPS dropped to "
                        + String.format("%.2f", tps) + " (<" + threshold + ").");
            } else {
                getLogger().info("[AET] TPS recovered to " + String.format("%.2f", tps)
                        + "; detection/replay work resumed.");
            }
        }
        return suspend;
    }

    public double getCurrentTps() {
        return refreshTpsSamples()[0];
    }

    public double[] getCurrentTpsSamples() {
        return refreshTpsSamples().clone();
    }

    private double[] refreshTpsSamples() {
        long now = System.currentTimeMillis();
        if (cachedTpsAtMillis > 0 && now >= cachedTpsAtMillis
                && now - cachedTpsAtMillis < TPS_CACHE_TTL_MILLIS) {
            return cachedTpsSamples;
        }

        try {
            double[] tps = Bukkit.getTPS();
            cachedTpsSamples = new double[] {
                    Math.min(20.0, tps[0]),
                    Math.min(20.0, tps[1]),
                    Math.min(20.0, tps[2])
            };
        } catch (Throwable ignored) {
            cachedTpsSamples = new double[] {-1.0, -1.0, -1.0};
        }
        cachedTpsAtMillis = now;
        return cachedTpsSamples;
    }
}
