package com.antielytratarget.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SchedulerUtil {

    private static final boolean FOLIA;

    static {
        boolean found;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            found = true;
        } catch (ClassNotFoundException e) {
            found = false;
        }
        FOLIA = found;
    }

    private SchedulerUtil() {}

public static boolean isFolia() {
        return FOLIA;
    }

public static void runTask(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

public static void runTaskLater(Plugin plugin, Runnable task, long delayTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

public static TaskWrapper runTaskTimer(Plugin plugin, Runnable task,
                                           long delayTicks, long periodTicks) {
        if (FOLIA) {
            var handle = Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, t -> task.run(), delayTicks, periodTicks);
            return handle::cancel;
        } else {
            var handle = Bukkit.getScheduler()
                    .runTaskTimer(plugin, task, delayTicks, periodTicks);
            return handle::cancel;
        }
    }

public static void runAsync(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

public static TaskWrapper runAsyncTimer(Plugin plugin, Runnable task,
                                            long delayTicks, long periodTicks) {
        if (FOLIA) {

long delayMs = delayTicks * 50L;
            long periodMs = periodTicks * 50L;
            var handle = Bukkit.getAsyncScheduler()
                    .runAtFixedRate(plugin, t -> task.run(),
                            delayMs, periodMs, TimeUnit.MILLISECONDS);
            return handle::cancel;
        } else {
            var handle = Bukkit.getScheduler()
                    .runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
            return handle::cancel;
        }
    }

public static void runForEntity(Plugin plugin, Entity entity,
                                    Runnable task, Runnable retired) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(),
                    retired != null ? retired : () -> {});
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

public static void runForEntityLater(Plugin plugin, Entity entity,
                                         Runnable task, Runnable retired,
                                         long delayTicks) {
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, t -> task.run(),
                    retired != null ? retired : () -> {}, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

public static TaskWrapper runForEntityTimer(Plugin plugin, Entity entity,
                                                Runnable task, Runnable retired,
                                                long delayTicks, long periodTicks) {
        if (FOLIA) {
            var handle = entity.getScheduler().runAtFixedRate(plugin, t -> task.run(),
                    retired != null ? retired : () -> {}, delayTicks, periodTicks);
            return () -> { if (handle != null) handle.cancel(); };
        } else {
            var handle = Bukkit.getScheduler()
                    .runTaskTimer(plugin, task, delayTicks, periodTicks);
            return handle::cancel;
        }
    }

    public static void forEachOnlinePlayer(Plugin plugin,
                                           Consumer<Player> action) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            runForEntity(plugin, player, () -> {
                if (player.isOnline()) action.accept(player);
            }, null);
        }
    }

    public static void teleport(Plugin plugin, Entity entity,
                                Location destination,
                                Consumer<Boolean> completion) {
        if (FOLIA) {
            entity.teleportAsync(destination).whenComplete((success, error) ->
                    runForEntity(plugin, entity,
                            () -> completion.accept(error == null
                                    && Boolean.TRUE.equals(success)),
                            () -> {}));
        } else {
            completion.accept(entity.teleport(destination));
        }
    }

    public static void teleport(Plugin plugin, Entity entity,
                                Location destination) {
        if (FOLIA) {
            entity.teleportAsync(destination);
        } else {
            entity.teleport(destination);
        }
    }

@FunctionalInterface
    public interface TaskWrapper {
        void cancel();
    }
}
