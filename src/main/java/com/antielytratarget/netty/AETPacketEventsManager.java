package com.antielytratarget.netty;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.Plugin;

public final class AETPacketEventsManager {

    private final AntiElytraTargetPlugin plugin;
    private PacketEventsAPI<Plugin> api;
    private PacketListenerCommon listener;
    private boolean loaded;
    private boolean started;

    public AETPacketEventsManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        if (loaded) return;

        try {
            api = SpigotPacketEventsBuilder.build(plugin);
            PacketEvents.setAPI(api);
            api.getSettings()
                    .fullStackTrace(true)
                    .kickOnPacketException(false)
                    .preViaInjection(true)
                    .checkForUpdates(false)
                    .reEncodeByDefault(false)
                    .bStats(false)
                    .debug(false);
            api.load();
            loaded = true;
            plugin.getLogger().info("[AET] PacketEvents API loaded and owned by AntiElytraTarget.");
        } catch (Throwable throwable) {
            cleanup();
            throw new IllegalStateException("AntiElytraTarget could not load its PacketEvents API", throwable);
        }
    }

    public void start() {
        if (started) return;
        if (!loaded) load();

        try {
            if (PacketEvents.getAPI() != api) {
                PacketEvents.setAPI(api);
            }
            listener = api.getEventManager().registerListener(new GrimPacketEventsListener(plugin));
            if (!api.isInitialized()) {
                api.init();
            }
            started = true;
            plugin.getLogger().info("[AET] PacketEvents packet checks started.");
        } catch (Throwable throwable) {
            cleanup();
            throw new IllegalStateException("AntiElytraTarget could not start its PacketEvents packet checks", throwable);
        }
    }

    public void stop() {
        cleanup();
    }

    public boolean isStarted() {
        return started;
    }

    private void cleanup() {
        try {
            if (listener != null && api != null) {
                api.getEventManager().unregisterListener(listener);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (api != null && !api.isTerminated()) {
                api.terminate();
            }
        } catch (Throwable ignored) {
        }
        try {
            SpigotPacketEventsBuilder.clearBuildCache();
        } catch (Throwable ignored) {
        }
        listener = null;
        api = null;
        loaded = false;
        started = false;
    }
}
