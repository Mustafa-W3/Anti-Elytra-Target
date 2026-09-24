package com.antielytratarget.netty;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.utils.SchedulerUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NettyManager implements Listener {

    private static final String HANDLER_NAME = "aet_packet_spy";

    private final AntiElytraTargetPlugin plugin;

private final Map<UUID, PlayerPacketData> dataMap = new ConcurrentHashMap<>();

private final Map<UUID, Channel> channelCache = new ConcurrentHashMap<>();

private final Map<UUID, User> userCache = new ConcurrentHashMap<>();

private SchedulerUtil.TaskWrapper transactionTask;

private volatile long serverTick = 0;

    public NettyManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        startTransactionTask();
    }

@EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        injectPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removePlayer(event.getPlayer());
    }

public PlayerPacketData getData(UUID uuid) {
        return dataMap.computeIfAbsent(uuid, k -> new PlayerPacketData());
    }

    public PlayerPacketData getExistingData(UUID uuid) {
        return dataMap.get(uuid);
    }

public long getServerTick() {
        return serverTick;
    }

public void shutdown() {
        if (transactionTask != null) transactionTask.cancel();
        for (Channel channel : channelCache.values()) {
            ejectChannel(channel);
        }
        dataMap.clear();
        channelCache.clear();
        userCache.clear();
    }

private void startTransactionTask() {
        transactionTask = SchedulerUtil.runTaskTimer(plugin, () -> {
            serverTick++;
            if (!plugin.getConfigManager().isSyntheticTransactionsEnabled()) {
                return;
            }
            int interval = plugin.getConfigManager().getSyntheticTransactionIntervalTicks();
            if (interval > 1 && serverTick % interval != 0) {
                return;
            }
            for (Map.Entry<UUID, PlayerPacketData> entry : dataMap.entrySet()) {
                User user = userCache.get(entry.getKey());
                if (user != null && plugin.getPacketEventsManager() != null
                        && plugin.getPacketEventsManager().isStarted()) {
                    entry.getValue().transactionTracker.sendTransaction(
                            user, serverTick);
                } else {
                    Channel ch = channelCache.get(entry.getKey());
                    if (ch != null && ch.isActive()) {
                        entry.getValue().transactionTracker.sendTransaction(ch, serverTick);
                    }
                }
            }
        }, 1L, 1L);
    }

private void injectPlayer(Player player) {
        PlayerPacketData packetData = new PlayerPacketData();
        var aet = plugin.getPlayerTracker().get(player);
        aet.setCurrentlyGliding(player.isGliding());
        aet.setInsideVehicle(player.isInsideVehicle());
        packetData.seedRotation(
                player.getLocation().getYaw(), player.getLocation().getPitch());
        packetData.silentFireworkState.seed(player);
        dataMap.put(player.getUniqueId(), packetData);
        Channel channel = getChannel(player);
        if (channel == null) {
            plugin.debug("[AET-Netty] Could not find channel for " + player.getName());
            return;
        }

        channelCache.put(player.getUniqueId(), channel);
        User user = resolveUser(player);
        if (user != null) userCache.put(player.getUniqueId(), user);
        PlayerPacketData data = dataMap.get(player.getUniqueId());
        ClientVersion clientVersion = resolveClientVersion(player);
        ServerVersion serverVersion = resolveServerVersion();

        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) return;
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                    new AETChannelHandler(
                            data, plugin, player,
                            clientVersion, serverVersion));
        });
    }

    public void injectOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SchedulerUtil.runForEntity(
                    plugin, player, () -> injectPlayer(player), null);
        }
    }

    private User resolveUser(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getUser(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ClientVersion resolveClientVersion(Player player) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ServerVersion resolveServerVersion() {
        try {
            return PacketEvents.getAPI().getServerManager().getVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ejectPlayer(Player player) {

Channel channel = getChannel(player);
        if (channel == null) channel = channelCache.get(player.getUniqueId());
        ejectChannel(channel);
    }

    private void ejectChannel(Channel channel) {
        if (channel == null) return;
        channel.eventLoop().submit(() -> {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        });
    }

    private void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        Channel leavingChannel = getChannel(player);

Channel currentChannel = channelCache.get(uuid);
        if (leavingChannel != null && leavingChannel != currentChannel) {
            final Channel oldChannel = leavingChannel;
            oldChannel.eventLoop().submit(() -> {
                if (oldChannel.pipeline().get(HANDLER_NAME) != null) {
                    oldChannel.pipeline().remove(HANDLER_NAME);
                }
            });
            return;
        }

        Player currentPlayer = plugin.getServer().getPlayer(uuid);
        if (currentPlayer != null && currentPlayer.isOnline() && currentPlayer != player) {
            return;
        }

        ejectPlayer(player);
        dataMap.remove(uuid);
        userCache.remove(uuid);
        if (currentChannel != null) {
            channelCache.remove(uuid, currentChannel);
        } else {
            channelCache.remove(uuid);
        }
    }

private static Channel getChannel(Player player) {
        try {
            Object channel = PacketEvents.getAPI().getPlayerManager()
                    .getChannel(player);
            if (channel instanceof Channel nettyChannel) {
                return nettyChannel;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object connection = craftPlayer.getClass().getField("connection").get(craftPlayer);
            Object networkManager = null;
            for (var field : connection.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(connection);
                if (val != null && Channel.class.isAssignableFrom(
                        val.getClass().getSuperclass() == null ? val.getClass()
                                : val.getClass().getSuperclass())) {
                    return (Channel) val;
                }
                if (val != null && val.getClass().getSimpleName().contains("Connection")) {
                    networkManager = val;
                }
            }
            if (networkManager != null) {
                for (var field : networkManager.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object val = field.get(networkManager);
                    if (val instanceof Channel ch) return ch;
                }

                for (var field : networkManager.getClass().getSuperclass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object val = field.get(networkManager);
                    if (val instanceof Channel ch) return ch;
                }
            }
        } catch (Exception e) {

        }
        return null;
    }
}
