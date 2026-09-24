package com.antielytratarget.replay;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.utils.MessageUtil;
import com.antielytratarget.utils.PermissionUtil;
import com.antielytratarget.utils.SchedulerUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReplayPlayer implements Listener {

    private static final int BACK_SLOT = 0;
    private static final int SPEED_DOWN_SLOT = 3;
    private static final int STOP_SLOT = 4;
    private static final int SPEED_UP_SLOT = 5;
    private static final int FORWARD_SLOT = 8;
    private static final int SEEK_FRAMES = 60;
    private static final int SESSION_START_DELAY_TICKS = 2;
    private static final int ISOLATION_REFRESH_TICKS = 10;
    private static final int LIVING_VISIBILITY_REFRESH_TICKS = 10;
    private static final int ACTION_BAR_REFRESH_TICKS = 5;
    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 4.0;

    private final AntiElytraTargetPlugin plugin;
    private final ReplayNpcAdapter npcAdapter;
    private final Map<UUID, PlaybackSession> sessions = new ConcurrentHashMap<>();
    private final Object sharedTickerLock = new Object();
    private SchedulerUtil.TaskWrapper sharedTickTask;

    public ReplayPlayer(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.npcAdapter = new ReplayNpcAdapter(plugin);
    }

    public void play(Player viewer, ReplayData data) {
        if (!PermissionUtil.canWatchReplay(viewer)) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + plugin.getConfigManager().getMsgNoPermission());
            return;
        }

        PlaybackSession existing = sessions.get(viewer.getUniqueId());
        if (existing != null) {
            stopPlayback(existing, false);
        }

        if (!npcAdapter.isReady()) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_adapter_unavailable", "<red>Replay NPC adapter is not available on this server version."));
            return;
        }

        if (data.getSnapshots().isEmpty()) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_no_frames", "<red>This replay has no recorded frames."));
            return;
        }

        ReplaySnapshot first = data.getSnapshots().get(0);
        World world = Bukkit.getWorld(first.getWorldName());
        if (world == null) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_world_not_found", "<red>World '{world}' not found.", "{world}", first.getWorldName()));
            return;
        }

        Location startLoc = toLocation(first);
        Location viewerLoc = startLoc.clone().add(3, 2, 3);
        viewerLoc.setYaw(startLoc.getYaw() + 180);
        viewerLoc.setPitch(20);
        PlaybackSession session = new PlaybackSession(viewer, data);
        sessions.put(viewer.getUniqueId(), session);
        SchedulerUtil.teleport(plugin, viewer, viewerLoc, success -> {
            if (sessions.get(viewer.getUniqueId()) != session) return;
            if (!success) {
                sessions.remove(viewer.getUniqueId(), session);
                MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                        + t("replay_teleport_failed",
                        "<red>Replay could not start because teleportation failed."));
                return;
            }
            beginPlayback(session, first);
        });
    }

    private void beginPlayback(PlaybackSession session, ReplaySnapshot first) {
        Player viewer = session.viewer;
        installControls(session);
        refreshViewerIsolation(session);
        if (!renderFrame(session, first, true)) {
            sessions.remove(viewer.getUniqueId(), session);
            cleanupSession(session);
            stopSharedTickerIfIdle();
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_npc_spawn_failed", "<red>Replay NPC could not be spawned. Check console for the exact NMS error."));
            return;
        }

        schedulePlayback(session);

        MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                + t("replay_playing", "<green>Playing replay of <yellow>{player} <gray>({frames} frames, {seconds}s)",
                "{player}", session.data.getPlayerName(), "{frames}",
                String.valueOf(session.data.getSnapshots().size()),
                "{seconds}", String.valueOf(session.data.getDurationSeconds())));
        MessageUtil.send(viewer, t("replay_hotbar_help", "<gray>Hotbar: <yellow>1 -3s <gray>| <yellow>4 -0.1x <gray>| <red>5 stop <gray>| <green>6 +0.1x <gray>| <green>9 +3s"));
    }

    public void stopPlayback(Player viewer) {
        stopPlayback(viewer, true);
    }

    private void stopPlayback(Player viewer, boolean notify) {
        PlaybackSession session = sessions.get(viewer.getUniqueId());
        if (session == null) return;

        stopPlayback(session, notify);
    }

    private boolean stopPlayback(PlaybackSession session, boolean notify) {
        Player viewer = session.viewer;
        if (!sessions.remove(viewer.getUniqueId(), session)) return false;

        cancelSessionTask(session);
        cleanupSession(session);
        stopSharedTickerIfIdle();

        if (notify && viewer.isOnline()) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_stop", "<yellow>Replay playback stopped."));
        }
        return true;
    }

    public boolean isWatching(UUID viewerUUID) {
        return sessions.containsKey(viewerUUID);
    }

    public void stopAll() {
        for (PlaybackSession session : new ArrayList<>(sessions.values())) {
            stopPlayback(session, false);
        }
        cancelSharedTicker();
    }

    public void seek(Player viewer, int deltaFrames) {
        PlaybackSession session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        int last = Math.max(0, session.data.getSnapshots().size() - 1);
        session.currentFrame = Math.max(0, Math.min(last, session.currentFrame + deltaFrames));
        if (!renderFrame(session, session.data.getSnapshots().get(session.currentFrame), true)) {
            if (stopPlayback(session, false)) {
                MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                        + t("replay_stopped_npc", "<red>Replay stopped because the NPC could not be spawned."));
            }
            return;
        }
        sendProgress(session);
    }

    public void adjustSpeed(Player viewer, double delta) {
        PlaybackSession session = sessions.get(viewer.getUniqueId());
        if (session == null) return;
        session.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, Math.round((session.speed + delta) * 10.0) / 10.0));
        session.frameAccumulator = 0.0;
        sendProgress(session);
    }

    private void schedulePlayback(PlaybackSession session) {
        if (SchedulerUtil.isFolia()) {
            session.tickTask = SchedulerUtil.runForEntityTimer(plugin, session.viewer,
                    () -> tickSessionSafely(session),
                    () -> retireSession(session),
                    SESSION_START_DELAY_TICKS, 1L);
            return;
        }

        ensureSharedTicker();
    }

    private void ensureSharedTicker() {
        synchronized (sharedTickerLock) {
            if (sharedTickTask != null) return;
            sharedTickTask = SchedulerUtil.runTaskTimer(plugin, this::tickSharedPlaybacks, 1L, 1L);
        }
    }

    private void tickSharedPlaybacks() {
        for (PlaybackSession session : sessions.values()) {
            if (session.sharedStartDelayTicks > 0) {
                session.sharedStartDelayTicks--;
                if (session.sharedStartDelayTicks > 0) continue;
            }
            tickSessionSafely(session);
        }
        stopSharedTickerIfIdle();
    }

    private void tickSessionSafely(PlaybackSession session) {
        if (sessions.get(session.viewer.getUniqueId()) != session) {
            cancelSessionTask(session);
            return;
        }

        try {
            tickPlayback(session);
        } catch (Throwable error) {
            plugin.getLogger().warning("[Replay] Playback tick failed for "
                    + session.viewer.getName() + ": " + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            try {
                stopPlayback(session, false);
            } catch (Throwable cleanupError) {
                plugin.getLogger().warning("[Replay] Playback cleanup failed for "
                        + session.viewer.getName() + ": " + cleanupError.getClass().getSimpleName()
                        + (cleanupError.getMessage() == null ? "" : ": " + cleanupError.getMessage()));
            }
        }
    }

    private void retireSession(PlaybackSession session) {
        session.tickTask = null;
        if (!sessions.remove(session.viewer.getUniqueId(), session)) return;
        cleanupSession(session);
    }

    private void cancelSessionTask(PlaybackSession session) {
        SchedulerUtil.TaskWrapper task = session.tickTask;
        session.tickTask = null;
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {
        }
    }

    private void stopSharedTickerIfIdle() {
        if (SchedulerUtil.isFolia() || !sessions.isEmpty()) return;
        synchronized (sharedTickerLock) {
            if (!sessions.isEmpty()) return;
            SchedulerUtil.TaskWrapper task = sharedTickTask;
            sharedTickTask = null;
            if (task != null) task.cancel();
        }
    }

    private void cancelSharedTicker() {
        synchronized (sharedTickerLock) {
            SchedulerUtil.TaskWrapper task = sharedTickTask;
            sharedTickTask = null;
            if (task != null) task.cancel();
        }
    }

    private void tickPlayback(PlaybackSession session) {
        if (!session.viewer.isOnline()) {
            stopPlayback(session, false);
            return;
        }

        List<ReplaySnapshot> snapshots = session.data.getSnapshots();
        if (session.currentFrame >= snapshots.size()) {
            Player viewer = session.viewer;
            if (stopPlayback(session, false)) {
                MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                        + t("replay_finished", "<green>Replay finished."));
            }
            return;
        }

        session.frameAccumulator += session.speed;
        int safety = 0;
        while (session.frameAccumulator >= 1.0 && session.currentFrame < snapshots.size() && safety++ < 20) {
            ReplaySnapshot snap = snapshots.get(session.currentFrame);
            if (!renderFrame(session, snap, false)) {
                Player viewer = session.viewer;
                if (stopPlayback(session, false)) {
                    MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                            + t("replay_stopped_npc", "<red>Replay stopped because the NPC could not be spawned."));
                }
                return;
            }
            announceFlagEvents(session, session.currentFrame);
            session.lastRenderedSnapshot = snap;
            session.currentFrame++;
            session.frameAccumulator -= 1.0;
        }

        if (session.currentFrame >= snapshots.size()) {
            Player viewer = session.viewer;
            if (stopPlayback(session, false)) {
                MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                        + t("replay_finished", "<green>Replay finished."));
            }
            return;
        }

        if (++session.isolationTicks >= ISOLATION_REFRESH_TICKS) {
            session.isolationTicks = 0;
            refreshViewerIsolation(session);
        }

        if (++session.livingVisibilityTicks >= LIVING_VISIBILITY_REFRESH_TICKS) {
            session.livingVisibilityTicks = 0;
            refreshLivingActorVisibility(session);
        }

        if (++session.actionBarTicks >= ACTION_BAR_REFRESH_TICKS) {
            session.actionBarTicks = 0;
            sendProgress(session);
        }
    }

    private boolean renderFrame(PlaybackSession session, ReplaySnapshot snap, boolean force) {
        Player viewer = session.viewer;
        ReplayFlightSimulator.Frame simulated = session.flightSimulator.advance(snap, force);
        Location loc = toLocation(
                snap.getWorldName(),
                simulated.x(), simulated.y(), simulated.z(),
                snap.getYaw(), snap.getPitch());
        if (loc == null) return true;

        if (session.primaryNpc == null || force || !npcAdapter.hasSkin(session.primaryNpc, snap.getSkin())) {
            if (session.primaryNpc != null) npcAdapter.destroy(viewer, session.primaryNpc);
            session.primaryNpc = npcAdapter.spawn(viewer,
                    replayUuid("primary:" + session.data.getPlayerUUID()),
                    session.data.getPlayerName(), loc, snap.getSkin());
            if (session.primaryNpc == null) {
                return false;
            }
        }
        npcAdapter.move(viewer, session.primaryNpc, loc, snap.isOnGround(),
                snap.isSwingArm() || snap.isFireworkStart(),
                force || simulated.hardCorrection());
        npcAdapter.updateEquipment(viewer, session.primaryNpc,
                snap.getMainHand(), snap.getOffHand(),
                snap.getHelmet(), visualChestplate(snap.getChestplate(), snap.isGliding()),
                snap.getLeggings(), snap.getBoots());
        npcAdapter.updateState(viewer, session.primaryNpc,
                snap.isSneaking(), snap.isSprinting(), snap.isGliding());
        if (!force && snap.isFireworkStart()) {
            playFireworkLaunch(viewer, loc);
        }

        renderActors(session, snap);
        if (!force) {
            playEffects(session, snap);
        }
        return true;
    }

    private void playFireworkLaunch(Player viewer, Location location) {
        try {
            Sound sound = resolveSound("ENTITY_FIREWORK_ROCKET_LAUNCH");
            if (sound != null) viewer.playSound(location, sound, 0.9f, 1.0f);
            Particle particle = resolveParticle("FIREWORK");
            if (particle == null) particle = resolveParticle("FIREWORKS_SPARK");
            if (particle != null) {
                viewer.spawnParticle(particle, location.clone().add(0.0, 0.6, 0.0),
                        5, 0.08, 0.08, 0.08, 0.01);
            }
        } catch (Throwable ignored) {
        }
    }

    private void announceFlagEvents(PlaybackSession session, int frameIndex) {
        for (ReplayFlagSnapshot flag :
                session.flagsByFrame.getOrDefault(frameIndex, Collections.emptyList())) {
            if (!session.shownFlags.add(flag)) continue;
            MessageUtil.send(session.viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_flag", "<red>Replay flag <dark_gray>| <yellow>{check} <gray>(total flags: <red>{flags}<gray>)",
                    "{check}", flag.getCheckName(), "{flags}", String.valueOf(flag.getTotalFlags())));
        }
    }

    private void renderActors(PlaybackSession session, ReplaySnapshot snap) {
        Player viewer = session.viewer;
        Set<String> aliveKeys = new HashSet<>();

        for (ReplayActorSnapshot actor : snap.getActors()) {
            if (SchedulerUtil.isFolia() && !actor.isPlayerLike()) continue;

            Location loc = toLocation(snap.getWorldName(), actor.getX(), actor.getY(), actor.getZ(),
                    actor.getYaw(), actor.getPitch());
            if (loc == null) continue;

            aliveKeys.add(actor.getKey());
            if (actor.isPlayerLike()) {
                ReplayNpcAdapter.Npc npc = session.playerActors.get(actor.getKey());
                if (npc != null && !npcAdapter.hasSkin(npc, actor.getSkin())) {
                    npcAdapter.destroy(viewer, npc);
                    session.playerActors.remove(actor.getKey());
                    npc = null;
                }
                if (npc == null && !session.failedPlayerActors.contains(actor.getKey())) {
                    npc = npcAdapter.spawn(viewer, replayUuid("actor:" + actor.getUuid()),
                            actor.getName(), loc, actor.getSkin());
                    if (npc != null) {
                        session.playerActors.put(actor.getKey(), npc);
                    } else {
                        session.failedPlayerActors.add(actor.getKey());
                    }
                }
                npcAdapter.move(viewer, npc, loc, !actor.isGliding(), actor.isSwingArm());
                npcAdapter.updateEquipment(viewer, npc,
                        actor.getMainHand(), actor.getOffHand(),
                        actor.getHelmet(), visualChestplate(actor.getChestplate(), actor.isGliding()),
                        actor.getLeggings(), actor.getBoots());
                npcAdapter.updateState(viewer, npc,
                        actor.isSneaking(), actor.isSprinting(), actor.isGliding());
                if (actor.isDamaged()) {
                    npcAdapter.hurt(viewer, npc);
                }
            } else {
                LivingEntity entity = session.livingActors.get(actor.getKey());
                boolean refreshVisibility = false;
                if (entity == null || entity.isDead()) {
                    if (entity != null) {
                        session.livingVisibilityStates.remove(entity.getUniqueId());
                    }
                    session.livingEquipmentKeys.remove(actor.getKey());
                    entity = spawnLivingActor(actor, loc);
                    if (entity != null) {
                        session.livingActors.put(actor.getKey(), entity);
                        refreshVisibility = true;
                    }
                }
                if (entity != null) {
                    SchedulerUtil.teleport(plugin, entity, loc);
                    applyLivingActor(session, actor, entity);
                    if (refreshVisibility) {
                        applyReplayEntityVisibility(session, entity);
                    }
                    if (actor.isDamaged()) {
                        entity.playEffect(EntityEffect.HURT);
                    }
                }
            }
        }

        cleanupMissingActors(session, aliveKeys);
    }

    private void playEffects(PlaybackSession session, ReplaySnapshot snap) {
        if (snap.getEffects().isEmpty()) return;

        for (ReplayEffectSnapshot effect : snap.getEffects()) {
            String worldName = effect.getWorldName() == null || effect.getWorldName().isBlank()
                    ? snap.getWorldName()
                    : effect.getWorldName();
            Location loc = toLocation(worldName, effect.getX(), effect.getY(), effect.getZ(), 0.0f, 0.0f);
            if (loc == null) continue;

            try {
                if (effect.getType() == ReplayEffectSnapshot.Type.PARTICLE) {
                    Particle particle = resolveParticle(effect.getName());
                    if (particle != null) {
                        session.viewer.spawnParticle(particle, loc,
                                Math.max(1, effect.getCount()),
                                effect.getOffsetX(), effect.getOffsetY(), effect.getOffsetZ(),
                                effect.getExtra());
                    }
                } else if (effect.getType() == ReplayEffectSnapshot.Type.SOUND) {
                    Sound sound = resolveSound(effect.getName());
                    if (sound != null) {
                        session.viewer.playSound(loc, sound,
                                Math.max(0.0f, effect.getVolume()),
                                Math.max(0.01f, effect.getPitch()));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private Particle resolveParticle(String name) {
        if (name == null || name.isBlank()) return null;
        try { return Particle.valueOf(name); } catch (Exception ignored) {}
        return null;
    }

    private Sound resolveSound(String name) {
        if (name == null || name.isBlank()) return null;
        try { return Sound.valueOf(name); } catch (Exception ignored) {}
        return null;
    }

    private LivingEntity spawnLivingActor(ReplayActorSnapshot actor, Location loc) {
        try {
            EntityType type = EntityType.valueOf(actor.getType());
            Entity entity = loc.getWorld().spawnEntity(loc, type);
            if (!(entity instanceof LivingEntity living)) {
                entity.remove();
                return null;
            }
            living.setInvulnerable(true);
            living.setSilent(true);
            living.setGravity(false);
            living.setPersistent(false);
            living.customName(MessageUtil.colorize("<gray>[REPLAY] <yellow>" + actor.getName()));
            living.setCustomNameVisible(false);
            try { living.setCollidable(false); } catch (Throwable ignored) {}
            try { living.setRemoveWhenFarAway(false); } catch (Throwable ignored) {}
            if (living instanceof Mob mob) {
                mob.setAI(false);
            }
            return living;
        } catch (Exception e) {
            plugin.debug("[Replay] Could not spawn actor " + actor.getType() + ": " + e.getMessage());
            return null;
        }
    }

    private void applyLivingActor(PlaybackSession session, ReplayActorSnapshot actor, LivingEntity entity) {
        String equipmentKey = String.join("|",
                ReplayItemCodec.normalize(actor.getMainHand()),
                ReplayItemCodec.normalize(actor.getOffHand()),
                ReplayItemCodec.normalize(actor.getHelmet()),
                ReplayItemCodec.normalize(actor.getChestplate()),
                ReplayItemCodec.normalize(actor.getLeggings()),
                ReplayItemCodec.normalize(actor.getBoots()));
        if (equipmentKey.equals(session.livingEquipmentKeys.get(actor.getKey()))) return;

        EntityEquipment eq = entity.getEquipment();
        if (eq != null) {
            eq.setItemInMainHand(toItem(actor.getMainHand()));
            eq.setItemInOffHand(toItem(actor.getOffHand()));
            eq.setHelmet(toItem(actor.getHelmet()));
            eq.setChestplate(toItem(actor.getChestplate()));
            eq.setLeggings(toItem(actor.getLeggings()));
            eq.setBoots(toItem(actor.getBoots()));
        }
        session.livingEquipmentKeys.put(actor.getKey(), equipmentKey);
    }

    private void applyReplayEntityVisibility(PlaybackSession session, LivingEntity entity) {
        Map<UUID, LivingVisibilityState> visibilityStates = session.livingVisibilityStates
                .computeIfAbsent(entity.getUniqueId(), ignored -> new HashMap<>());
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean visible = player.equals(session.viewer) || PermissionUtil.canWatchReplay(player);
            LivingVisibilityState previous = visibilityStates.get(player.getUniqueId());
            if (previous != null && previous.player == player && previous.visible == visible) continue;

            try {
                if (visible) {
                    player.showEntity(plugin, entity);
                } else {
                    player.hideEntity(plugin, entity);
                }
                visibilityStates.put(player.getUniqueId(), new LivingVisibilityState(player, visible));
            } catch (Throwable ignored) {
            }
        }
    }

    private void refreshLivingActorVisibility(PlaybackSession session) {
        for (LivingEntity entity : session.livingActors.values()) {
            if (entity != null && !entity.isDead()) {
                applyReplayEntityVisibility(session, entity);
            }
        }
    }

    private void refreshViewerIsolation(PlaybackSession session) {
        Player viewer = session.viewer;
        if (!viewer.isOnline()) return;

        double radius = Math.max(8.0, plugin.getConfigManager().getReplayIsolationRadius());
        for (Entity entity : viewer.getNearbyEntities(radius, radius, radius)) {
            if (entity.equals(viewer) || session.livingActors.containsValue(entity)) continue;
            if (entity instanceof Player player) {
                UUID uuid = player.getUniqueId();
                Player previousInstance = session.hiddenPlayerInstances.get(uuid);
                if (previousInstance != null && previousInstance != player) {
                    session.hiddenPlayers.remove(uuid);
                    session.hiddenPlayerInstances.remove(uuid);
                }
                if (!session.hiddenPlayers.add(uuid)) continue;
                try {
                    viewer.hidePlayer(plugin, player);
                    session.hiddenPlayerInstances.put(uuid, player);
                } catch (Throwable ignored) {
                    session.hiddenPlayers.remove(uuid);
                    session.hiddenPlayerInstances.remove(uuid);
                }
            } else {
                UUID uuid = entity.getUniqueId();
                if (!session.hiddenEntities.add(uuid)) continue;
                try {
                    viewer.hideEntity(plugin, entity);
                } catch (Throwable ignored) {
                    session.hiddenEntities.remove(uuid);
                }
            }
        }
    }

    private void restoreViewerIsolation(PlaybackSession session) {
        Player viewer = session.viewer;
        if (!viewer.isOnline()) return;

        for (UUID uuid : session.hiddenPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                try { viewer.showPlayer(plugin, player); } catch (Throwable ignored) {}
            }
        }
        for (UUID uuid : session.hiddenEntities) {
            Entity entity = findEntity(uuid);
            if (entity != null) {
                try { viewer.showEntity(plugin, entity); } catch (Throwable ignored) {}
            }
        }
        session.hiddenPlayers.clear();
        session.hiddenPlayerInstances.clear();
        session.hiddenEntities.clear();
    }

    private Entity findEntity(UUID uuid) {
        try {
            return Bukkit.getEntity(uuid);
        } catch (NoSuchMethodError ignored) {
            for (World world : Bukkit.getWorlds()) {
                Entity entity = world.getEntity(uuid);
                if (entity != null) return entity;
            }
        }
        return null;
    }

    private void cleanupMissingActors(PlaybackSession session, Set<String> aliveKeys) {
        Iterator<Map.Entry<String, ReplayNpcAdapter.Npc>> npcIt = session.playerActors.entrySet().iterator();
        while (npcIt.hasNext()) {
            Map.Entry<String, ReplayNpcAdapter.Npc> entry = npcIt.next();
            if (!aliveKeys.contains(entry.getKey())) {
                npcAdapter.destroy(session.viewer, entry.getValue());
                npcIt.remove();
            }
        }

        Iterator<Map.Entry<String, LivingEntity>> livingIt = session.livingActors.entrySet().iterator();
        while (livingIt.hasNext()) {
            Map.Entry<String, LivingEntity> entry = livingIt.next();
            if (!aliveKeys.contains(entry.getKey())) {
                LivingEntity entity = entry.getValue();
                session.livingEquipmentKeys.remove(entry.getKey());
                session.livingVisibilityStates.remove(entity.getUniqueId());
                entity.remove();
                livingIt.remove();
            }
        }
    }

    private void despawnAll(PlaybackSession session) {
        if (session.primaryNpc != null) {
            npcAdapter.destroy(session.viewer, session.primaryNpc);
            session.primaryNpc = null;
        }
        for (ReplayNpcAdapter.Npc npc : session.playerActors.values()) {
            npcAdapter.destroy(session.viewer, npc);
        }
        session.playerActors.clear();
        for (LivingEntity entity : session.livingActors.values()) {
            if (entity != null && !entity.isDead()) entity.remove();
        }
        session.livingActors.clear();
        session.livingEquipmentKeys.clear();
        session.livingVisibilityStates.clear();
    }

    private void cleanupSession(PlaybackSession session) {
        try {
            despawnAll(session);
        } catch (Throwable error) {
            plugin.debug("[Replay] Could not despawn playback actors: " + error.getMessage());
        }
        try {
            restoreViewerIsolation(session);
        } catch (Throwable error) {
            plugin.debug("[Replay] Could not restore viewer isolation: " + error.getMessage());
        }
        try {
            restoreControls(session);
        } catch (Throwable error) {
            plugin.debug("[Replay] Could not restore playback controls: " + error.getMessage());
        }
        try {
            restoreViewerState(session);
        } catch (Throwable error) {
            plugin.debug("[Replay] Could not restore viewer state: " + error.getMessage());
        }
    }

    private void installControls(PlaybackSession session) {
        Player viewer = session.viewer;
        viewer.setInvulnerable(true);
        try { viewer.setCollidable(false); } catch (Throwable ignored) {}
        viewer.setAllowFlight(true);
        viewer.setFlying(true);
        PlayerInventory inv = viewer.getInventory();
        session.savedContents = cloneArray(inv.getContents());
        session.savedArmor = cloneArray(inv.getArmorContents());
        try {
            session.savedExtra = cloneArray(inv.getExtraContents());
        } catch (Throwable ignored) {
            session.savedExtra = new ItemStack[0];
        }
        session.savedHeldSlot = inv.getHeldItemSlot();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        try { inv.setExtraContents(new ItemStack[0]); } catch (Throwable ignored) {}
        try { inv.setItemInOffHand(null); } catch (Throwable ignored) {}

        inv.setItem(BACK_SLOT, controlItem(Material.ARROW, "<yellow><< 3s"));
        inv.setItem(SPEED_DOWN_SLOT, controlItem(Material.REDSTONE, "<yellow>-0.1x"));
        inv.setItem(STOP_SLOT, controlItem(Material.BARRIER, t("replay_stop_item", "<red>Stop Replay")));
        inv.setItem(SPEED_UP_SLOT, controlItem(Material.SUGAR, "<green>+0.1x"));
        inv.setItem(FORWARD_SLOT, controlItem(Material.ARROW, "<green>3s >>"));
        inv.setHeldItemSlot(STOP_SLOT);
    }

    private void restoreControls(PlaybackSession session) {
        if (!session.viewer.isOnline()) return;
        PlayerInventory inv = session.viewer.getInventory();
        if (session.savedContents != null) inv.setContents(cloneArray(session.savedContents));
        if (session.savedArmor != null) inv.setArmorContents(cloneArray(session.savedArmor));
        if (session.savedExtra != null) {
            try { inv.setExtraContents(cloneArray(session.savedExtra)); } catch (Throwable ignored) {}
        }
        inv.setHeldItemSlot(Math.max(0, Math.min(8, session.savedHeldSlot)));
    }

    private void restoreViewerState(PlaybackSession session) {
        if (!session.viewer.isOnline()) return;
        Player viewer = session.viewer;
        viewer.setInvulnerable(session.savedInvulnerable);
        try { viewer.setCollidable(session.savedCollidable); } catch (Throwable ignored) {}
        viewer.setAllowFlight(session.savedAllowFlight);
        viewer.setFlying(session.savedAllowFlight && session.savedFlying);
        viewer.setGameMode(session.savedGameMode);
        if (session.savedLocation != null) {
            SchedulerUtil.teleport(plugin, viewer, session.savedLocation);
        }
    }

    private ItemStack controlItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.colorize(name));
            meta.lore(List.of(
                    Component.text("AET replay control")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void sendProgress(PlaybackSession session) {
        int total = Math.max(1, session.data.getSnapshots().size());
        int pct = (int) ((session.currentFrame * 100.0) / total);
        int currentSec = session.currentFrame / 20;
        sendActionBar(session.viewer,
                t("replay_progress", "<dark_gray>[<yellow>Replay<dark_gray>] <gray>{current}s / {duration}s <dark_gray>(<white>{percent}%<dark_gray>) <gray>Speed: <white>{speed}",
                        "{current}", String.valueOf(currentSec), "{duration}", String.valueOf(session.data.getDurationSeconds()),
                        "{percent}", String.valueOf(pct), "{speed}", String.format(Locale.ROOT, "%.1fx", session.speed)));
    }

    private void sendActionBar(Player player, String message) {
        try {
            player.sendActionBar(MessageUtil.colorize(message));
        } catch (Exception ignored) {}
    }

    private String t(String key, String fallback, String... replacements) {
        String value = plugin.getConfigManager().msg(key, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    private Location toLocation(ReplaySnapshot snap) {
        return toLocation(snap.getWorldName(), snap.getX(), snap.getY(), snap.getZ(),
                snap.getYaw(), snap.getPitch());
    }

    private Location toLocation(String worldName, double x, double y, double z, float yaw, float pitch) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }

    private ItemStack toItem(String materialName) {
        return ReplayItemCodec.decode(materialName);
    }

    private String visualChestplate(String chestplate, boolean gliding) {
        if (!gliding) return chestplate;
        if (chestplate == null || chestplate.isBlank() || chestplate.equals("AIR")) {
            return "ELYTRA";
        }
        return chestplate;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private ItemStack[] cloneArray(ItemStack[] items) {
        if (items == null) return null;
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = cloneItem(items[i]);
        }
        return copy;
    }

    private UUID replayUuid(String seed) {
        return UUID.nameUUIDFromBytes(("AETReplay:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        PlaybackSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        event.setCancelled(true);

        int slot = event.getPlayer().getInventory().getHeldItemSlot();
        if (slot == BACK_SLOT) {
            seek(event.getPlayer(), -SEEK_FRAMES);
        } else if (slot == SPEED_DOWN_SLOT) {
            adjustSpeed(event.getPlayer(), -0.1);
        } else if (slot == FORWARD_SLOT) {
            seek(event.getPlayer(), SEEK_FRAMES);
        } else if (slot == SPEED_UP_SLOT) {
            adjustSpeed(event.getPlayer(), 0.1);
        } else if (slot == STOP_SLOT) {
            stopPlayback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onViewerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            stopPlayback(event.getPlayer(), false);
        }
    }

    private static final class LivingVisibilityState {
        final Player player;
        final boolean visible;

        LivingVisibilityState(Player player, boolean visible) {
            this.player = player;
            this.visible = visible;
        }
    }

    private static class PlaybackSession {
        final Player viewer;
        final ReplayData data;
        final Map<String, ReplayNpcAdapter.Npc> playerActors = new HashMap<>();
        final Map<String, LivingEntity> livingActors = new HashMap<>();
        final Map<String, String> livingEquipmentKeys = new HashMap<>();
        final Map<UUID, Map<UUID, LivingVisibilityState>> livingVisibilityStates = new HashMap<>();
        final Set<String> failedPlayerActors = new HashSet<>();
        final Set<ReplayFlagSnapshot> shownFlags = new HashSet<>();
        final Map<Integer, List<ReplayFlagSnapshot>> flagsByFrame = new HashMap<>();
        final Set<UUID> hiddenPlayers = new HashSet<>();
        final Map<UUID, Player> hiddenPlayerInstances = new HashMap<>();
        final Set<UUID> hiddenEntities = new HashSet<>();
        final ReplayFlightSimulator flightSimulator = new ReplayFlightSimulator();
        final Location savedLocation;
        final GameMode savedGameMode;
        final boolean savedInvulnerable;
        final boolean savedCollidable;
        final boolean savedAllowFlight;
        final boolean savedFlying;
        ItemStack[] savedContents;
        ItemStack[] savedArmor;
        ItemStack[] savedExtra;
        int savedHeldSlot;
        int currentFrame = 0;
        double speed = 1.0;
        double frameAccumulator = 0.0;
        int actionBarTicks;
        int isolationTicks;
        int livingVisibilityTicks;
        int sharedStartDelayTicks = SESSION_START_DELAY_TICKS;
        ReplaySnapshot lastRenderedSnapshot;
        volatile SchedulerUtil.TaskWrapper tickTask;
        ReplayNpcAdapter.Npc primaryNpc;

        PlaybackSession(Player viewer, ReplayData data) {
            this.viewer = viewer;
            this.data = data;
            this.savedLocation = viewer.getLocation().clone();
            this.savedGameMode = viewer.getGameMode();
            this.savedInvulnerable = viewer.isInvulnerable();
            this.savedCollidable = viewer.isCollidable();
            this.savedAllowFlight = viewer.getAllowFlight();
            this.savedFlying = viewer.isFlying();
            for (ReplayFlagSnapshot flag : data.getFlagEvents()) {
                flagsByFrame.computeIfAbsent(flag.getFrameIndex(), ignored -> new ArrayList<>())
                        .add(flag);
            }
        }
    }
}
