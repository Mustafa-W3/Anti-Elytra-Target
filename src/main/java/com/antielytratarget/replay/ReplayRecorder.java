package com.antielytratarget.replay;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.netty.PlayerPacketData;
import com.antielytratarget.utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ReplayRecorder implements Listener {

    private static final int RECORDING_TRIGGER_FLAGS = 2;
    private static final int ACTOR_CAPTURE_INTERVAL_TICKS = 2;
    private static final int FIREWORK_BASE_LIFETIME_TICKS = 10;

    public static void clearStaticCaches() {
        ReplayItemCodec.clearCaches();
        ReplaySkinSnapshot.clearCache();
    }

    private final AntiElytraTargetPlugin plugin;
    private final File replayDir;

private final Map<UUID, RecordingSession> activeSessions = new ConcurrentHashMap<>();

private final Map<UUID, Queue<ReplayEffectSnapshot>> pendingEffects = new ConcurrentHashMap<>();

private final Map<UUID, Map<UUID, Long>> priorityTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Long> recentlyDamagedActors = new ConcurrentHashMap<>();

    private final Queue<ReplayData> pendingSaves = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> pendingLoads = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean ioWorkerRunning = new AtomicBoolean();
    private final Object ioDrainLock = new Object();
    private final Object sessionLifecycleLock = new Object();
    private final Map<Class<?>, List<Field>> reflectedFields = new ConcurrentHashMap<>();

    private volatile boolean stopping;
    private volatile boolean shutdownFlushComplete;

    public ReplayRecorder(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.replayDir = new File(plugin.getDataFolder(), "replays");
        if (!replayDir.exists()) replayDir.mkdirs();
    }

public void start() {
        stopping = false;
        shutdownFlushComplete = false;
    }

public void stop() {
        stopping = true;

        synchronized (sessionLifecycleLock) {
            for (UUID uuid : new ArrayList<>(activeSessions.keySet())) {
                finishSession(uuid);
            }
        }

        pendingLoads.clear();
        pendingEffects.clear();
        priorityTargets.clear();
        recentlyDamagedActors.clear();
        reflectedFields.clear();
        shutdownFlushComplete = true;
        drainIoQueue();
    }

public void onFlag(Player player, String checkName, int totalFlags) {
        if (stopping) return;
        if (!plugin.getConfigManager().isReplayEnabled()) return;

        UUID uuid = player.getUniqueId();

        RecordingSession session = activeSessions.get(uuid);
        if (session != null) {
            recordFlagEvent(session, checkName, totalFlags);
            return;
        }

        if (totalFlags >= RECORDING_TRIGGER_FLAGS) {
            session = startSession(player, checkName + " (" + totalFlags + " flags)");
            recordFlagEvent(session, checkName, totalFlags);
        }
    }

private RecordingSession startSession(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        int duration = plugin.getConfigManager().getReplayDuration();
        ReplayData data = new ReplayData(uuid, player.getName(), reason, duration);
        RecordingSession session = new RecordingSession(data, Math.max(1, duration * 20));
        synchronized (sessionLifecycleLock) {
            if (stopping) return null;
            RecordingSession existing = activeSessions.putIfAbsent(uuid, session);
            if (existing != null) return existing;
        }

        plugin.debug("[Replay] Started recording for " + player.getName() + " — reason: " + reason);

        session.tickTask = SchedulerUtil.runForEntityTimer(plugin, player,
                () -> captureSessionTick(uuid, player, session),
                () -> {
                    if (activeSessions.remove(uuid, session)) {
                        finishSession(uuid, session);
                    }
                },
                1L, 1L);
        plugin.debug("[Replay] Recording starts at the second flag for " + player.getName());
        return session;
    }

    private void captureSessionTick(UUID uuid, Player player, RecordingSession session) {
        if (stopping || !plugin.getConfigManager().isReplayEnabled()
                || !player.isOnline() || activeSessions.get(uuid) != session) {
            if (activeSessions.remove(uuid, session)) finishSession(uuid, session);
            return;
        }

        boolean finish;
        synchronized (session) {
            if (session.finished) return;

            if (!plugin.isRuntimeSuspendedForTps()) {
                int frameIndex = session.frameIndex;
                boolean swing = session.pendingSwing.getAndSet(false);
                if (frameIndex % ACTOR_CAPTURE_INTERVAL_TICKS == 0) {
                    session.cachedActors = captureActors(player,
                            swing ? Set.of(uuid) : Collections.emptySet());
                }

                int startedPower = session.pendingFireworkPower.getAndSet(0);
                boolean fireworkStart = startedPower > 0;
                if (fireworkStart) {
                    session.activeFireworkPower = startedPower;
                    session.fireworkTicksRemaining =
                            FIREWORK_BASE_LIFETIME_TICKS * (startedPower + 1);
                }
                int activeFireworkPower = session.fireworkTicksRemaining > 0
                        ? session.activeFireworkPower : 0;

                ReplaySnapshot snapshot = captureSnapshot(
                        player,
                        frameIndex * 50L,
                        swing,
                        session.cachedActors,
                        activeFireworkPower,
                        fireworkStart
                );
                session.data.addSnapshot(snapshot);
                session.frameIndex++;
                if (session.fireworkTicksRemaining > 0) session.fireworkTicksRemaining--;
                if ((session.frameIndex % 20) == 0) cleanupExpiredMetadata();
            } else {
                session.pendingSwing.set(false);
            }

            session.remainingTicks--;
            finish = session.remainingTicks <= 0;
        }

        if (finish && activeSessions.remove(uuid, session)) {
            finishSession(uuid, session);
        }
    }

    private void recordFlagEvent(RecordingSession session, String checkName, int totalFlags) {
        if (session == null) return;
        synchronized (session) {
            if (session.finished) return;
            int frameIndex = Math.max(0, session.frameIndex);
            session.data.addFlagEvent(new ReplayFlagSnapshot(frameIndex, frameIndex * 50L, checkName, totalFlags));
        }
    }

private ReplaySnapshot captureSnapshot(Player player,
                                       long tickOffset,
                                       boolean swingArm,
                                       List<ReplayActorSnapshot> actors,
                                       int fireworkPower,
                                       boolean fireworkStart) {
        var loc = player.getLocation();
        PlayerInventory inv = player.getInventory();
        Vector velocity = player.getVelocity();

        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();
        boolean onGround = player.isOnGround();
        boolean packetBacked = false;
        long movementTick = -1L;

        PlayerPacketData packetData = plugin.getNettyManager() != null
                ? plugin.getNettyManager().getData(player.getUniqueId())
                : null;
        if (packetData != null) {
            long now = System.currentTimeMillis();
            double[] packetPosition = packetData.lastPosition.get();
            float[] packetRotation = packetData.lastRotation.get();

            if (packetPosition != null && now - packetData.lastMovementPacketTime.get() <= 250L) {
                x = packetPosition[0];
                y = packetPosition[1];
                z = packetPosition[2];
                packetBacked = true;
                double[] packetVelocity = packetData.computeVelocity();
                if (packetVelocity != null) {
                    velocity = new Vector(packetVelocity[0], packetVelocity[1], packetVelocity[2]);
                }
            }
            if (packetRotation != null && now - packetData.lastRotationPacketTime.get() <= 250L) {
                yaw = packetRotation[0];
                pitch = packetRotation[1];
                packetBacked = true;
            }
            onGround = packetData.lastOnGround.get();
            movementTick = packetData.movementTick.get();
        }
        List<ReplayEffectSnapshot> effects = drainPendingEffects(player.getUniqueId());

        return new ReplaySnapshot(
                x, y, z,
                yaw, pitch,
                loc.getWorld() != null ? loc.getWorld().getName() : "world",
                player.isSneaking(),
                player.isSprinting(),
                player.isGliding(),
                player.isBlocking(),
                swingArm,
                itemPayload(inv.getItemInMainHand()),
                itemPayload(inv.getItemInOffHand()),
                itemPayload(inv.getHelmet()),
                itemPayload(inv.getChestplate()),
                itemPayload(inv.getLeggings()),
                itemPayload(inv.getBoots()),
                tickOffset,
                velocity.getX(), velocity.getY(), velocity.getZ(),
                onGround,
                packetBacked,
                plugin.getNettyManager() != null ? plugin.getNettyManager().getServerTick() : -1L,
                movementTick,
                actors,
                ReplaySkinSnapshot.fromPlayer(player),
                effects,
                player.getEyeHeight(),
                fireworkPower,
                fireworkStart
        );
    }

private void finishSession(UUID uuid) {
        RecordingSession session = activeSessions.remove(uuid);
        if (session != null) {
            finishSession(uuid, session);
        }
    }

    private void finishSession(UUID uuid, RecordingSession session) {
        pendingEffects.remove(uuid);
        priorityTargets.remove(uuid);
        if (activeSessions.isEmpty()) {
            pendingEffects.clear();
            priorityTargets.clear();
            recentlyDamagedActors.clear();
        }
        ReplayData data;
        SchedulerUtil.TaskWrapper task;
        synchronized (session) {
            if (session.finished) return;
            session.finished = true;
            task = session.tickTask;
            session.tickTask = null;
            data = session.data;
        }
        if (task != null) task.cancel();
        if (data.snapshotCount() == 0) return;

        pendingSaves.offer(data);
        if (stopping) {
            if (shutdownFlushComplete) drainIoQueue();
        } else {
            startIoWorker();
        }
    }

    private void startIoWorker() {
        if (stopping) return;
        if (!ioWorkerRunning.compareAndSet(false, true)) return;
        try {
            SchedulerUtil.runAsync(plugin, this::drainIoQueue);
        } catch (RuntimeException e) {
            ioWorkerRunning.set(false);
            if (!stopping) {
                plugin.getLogger().warning("[Replay] Could not start replay I/O worker: " + e.getMessage());
            }
        }
    }

    private void drainIoQueue() {
        synchronized (ioDrainLock) {
            boolean savedAny = false;
            try {
                ReplayData data;
                while ((data = pendingSaves.poll()) != null) {
                    savedAny = true;
                    saveReplay(data);
                }
                if (savedAny) {
                    pruneOldReplays();
                }

                Runnable loadTask;
                while (!stopping && (loadTask = pendingLoads.poll()) != null) {
                    loadTask.run();
                }
            } finally {
                ioWorkerRunning.set(false);
                if (!stopping && (!pendingSaves.isEmpty() || !pendingLoads.isEmpty())) {
                    startIoWorker();
                }
            }
        }
    }

    private void saveReplay(ReplayData data) {
        try {
            File file = new File(replayDir, data.getFileName());
            data.saveToFile(file);
            plugin.getLogger().info("[Replay] Saved replay: " + file.getName()
                    + " (" + data.snapshotCount() + " frames)");
        } catch (Exception e) {
            plugin.getLogger().severe("[Replay] Failed to save replay for "
                    + data.getPlayerName() + ": " + e.getMessage());
        }
    }

    private void cleanupExpiredMetadata() {
        long now = System.currentTimeMillis();
        recentlyDamagedActors.entrySet().removeIf(entry -> entry.getValue() < now);
        priorityTargets.forEach((sourceUuid, ignored) ->
                priorityTargets.computeIfPresent(sourceUuid, (uuid, targets) -> {
                    targets.entrySet().removeIf(entry -> entry.getValue() < now);
                    return targets.isEmpty() ? null : targets;
                }));
    }

    private static final class RecordingSession {
        private final ReplayData data;
        private final AtomicBoolean pendingSwing = new AtomicBoolean();
        private final AtomicInteger pendingFireworkPower = new AtomicInteger();
        private List<ReplayActorSnapshot> cachedActors = Collections.emptyList();
        private SchedulerUtil.TaskWrapper tickTask;
        private int remainingTicks;
        private int frameIndex;
        private int activeFireworkPower;
        private int fireworkTicksRemaining;
        private boolean finished;

        private RecordingSession(ReplayData data, int remainingTicks) {
            this.data = data;
            this.remainingTicks = remainingTicks;
        }
    }

public boolean isRecording(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

public File[] getSavedReplays() {
        File[] files = replayDir.listFiles((dir, name) -> name.endsWith(".replay"));
        if (files == null) return new File[0];

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

public ReplayData loadReplay(File file) {
        try {
            return ReplayData.loadFromFile(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to load replay: "
                    + file.getName() + " — " + e.getMessage());
            return null;
        }
    }

    public void loadReplayAsync(File file, Consumer<ReplayData> callback) {
        if (file == null || callback == null || stopping) return;
        Runnable loadTask = () -> {
            ReplayData data = loadReplay(file);
            try {
                callback.accept(data);
            } catch (Throwable error) {
                plugin.debug("[Replay] Async replay load callback failed: " + error.getMessage());
            }
        };
        pendingLoads.offer(loadTask);
        if (stopping) {
            pendingLoads.remove(loadTask);
            return;
        }
        startIoWorker();
    }

    public void loadReplaySummariesAsync(Collection<File> files,
                                         Consumer<Map<File, ReplayData.Summary>> callback) {
        if (files == null || files.isEmpty() || callback == null || stopping) return;
        List<File> requestedFiles = List.copyOf(files);
        Runnable loadTask = () -> {
            Map<File, ReplayData.Summary> summaries = new LinkedHashMap<>();
            for (File file : requestedFiles) {
                summaries.put(file, loadSummary(file));
            }
            try {
                callback.accept(Collections.unmodifiableMap(summaries));
            } catch (Throwable error) {
                plugin.debug("[Replay] Async replay summary callback failed: " + error.getMessage());
            }
        };
        pendingLoads.offer(loadTask);
        if (stopping) {
            pendingLoads.remove(loadTask);
            return;
        }
        startIoWorker();
    }

public boolean deleteReplay(File file) {
        return file.delete();
    }

public File getReplayDir() {
        return replayDir;
    }

    public void markCombatTarget(Player attacker, LivingEntity target) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        if (attacker == null || target == null || attacker.equals(target)) return;
        if (!activeSessions.containsKey(attacker.getUniqueId())) return;

        long until = System.currentTimeMillis() + plugin.getConfigManager().getReplayTargetKeepSeconds() * 1000L;
        priorityTargets
                .computeIfAbsent(attacker.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(target.getUniqueId(), until);
    }

    public void markFireworkBoost(Player player, int power) {
        if (player == null || power <= 0) return;
        RecordingSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.pendingFireworkPower.accumulateAndGet(Math.max(1, power), Math::max);
        }
    }

    public File findLatestReplay(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        for (File file : getSavedReplays()) {
            ReplayData.Summary summary = loadSummary(file);
            if (summary != null && summary.getPlayerName().equalsIgnoreCase(playerName)) {
                return file;
            }
        }
        return null;
    }

    public int deleteReplays(String playerName) {
        if (playerName == null || playerName.isBlank()) return 0;
        int deleted = 0;
        for (File file : getSavedReplays()) {
            ReplayData.Summary summary = loadSummary(file);
            if (summary != null && summary.getPlayerName().equalsIgnoreCase(playerName) && deleteReplay(file)) {
                deleted++;
            }
        }
        return deleted;
    }

    public List<String> getReplayPlayerNames() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (File file : getSavedReplays()) {
            ReplayData.Summary summary = loadSummary(file);
            if (summary != null) names.add(summary.getPlayerName());
        }
        return new ArrayList<>(names);
    }

    private ReplayData.Summary loadSummary(File file) {
        try {
            return ReplayData.loadSummaryFromFile(file);
        } catch (Exception e) {
            plugin.debug("[Replay] Failed to read replay summary: "
                    + file.getName() + " - " + e.getMessage());
            return null;
        }
    }

    private List<ReplayActorSnapshot> captureActors(Player source, Set<UUID> swungThisTick) {
        double radius = plugin.getConfigManager().getReplayEntityRadius();
        int maxActors = plugin.getConfigManager().getReplayMaxActorsPerFrame();
        if (radius <= 0 || maxActors <= 0) return Collections.emptyList();

        Map<UUID, LivingEntity> actors = new LinkedHashMap<>();
        for (Entity entity : source.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof LivingEntity living && !living.getUniqueId().equals(source.getUniqueId())) {
                actors.put(living.getUniqueId(), living);
                if (actors.size() >= maxActors) break;
            }
        }

        Map<UUID, Long> targets = priorityTargets.get(source.getUniqueId());
        if (targets != null && !targets.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Long> entry : targets.entrySet()) {
                if (entry.getValue() < now) continue;
                UUID targetUuid = entry.getKey();
                if (actors.containsKey(targetUuid)) continue;

                Entity targetEntity = Bukkit.getEntity(targetUuid);
                LivingEntity target = targetEntity instanceof LivingEntity living ? living : null;
                if (target != null && !target.getUniqueId().equals(source.getUniqueId())) {
                    actors.putIfAbsent(target.getUniqueId(), target);
                }
            }
        }

        List<ReplayActorSnapshot> snapshots = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (LivingEntity actor : actors.values()) {
            boolean damaged = recentlyDamagedActors.getOrDefault(actor.getUniqueId(), 0L) >= now;
            snapshots.add(ReplayActorSnapshot.fromEntity(actor, swungThisTick.contains(actor.getUniqueId()), damaged));
            if (snapshots.size() >= maxActors) break;
        }
        return snapshots;
    }

    private String itemPayload(ItemStack item) {
        return ReplayItemCodec.encode(item);
    }

    private List<ReplayEffectSnapshot> drainPendingEffects(UUID uuid) {
        Queue<ReplayEffectSnapshot> queue = pendingEffects.get(uuid);
        if (queue == null || queue.isEmpty()) return Collections.emptyList();

        int maxEffects = Math.max(0, plugin.getConfigManager().getReplayMaxEffectsPerFrame());
        if (maxEffects <= 0) {
            pendingEffects.remove(uuid, queue);
            return Collections.emptyList();
        }

        List<ReplayEffectSnapshot> effects = new ArrayList<>();
        for (int i = 0; i < maxEffects; i++) {
            ReplayEffectSnapshot effect = queue.poll();
            if (effect == null) break;
            effects.add(effect);
        }
        return effects;
    }

    private void queueEffect(Player replaySource, ReplayEffectSnapshot effect) {
        if (replaySource == null || effect == null) return;
        if (!shouldCaptureOutboundEffects(replaySource.getUniqueId())) return;
        offerPendingEffect(replaySource.getUniqueId(), effect);
    }

    public void captureOutboundPacket(UUID recipientUuid, Object packet) {
        if (recipientUuid == null || packet == null) return;
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        if (!shouldCaptureOutboundEffects(recipientUuid)) return;

        ReplayEffectSnapshot effect = particlePacketToEffect(packet);
        if (effect == null) return;

        offerPendingEffect(recipientUuid, effect);
    }

    private boolean shouldCaptureOutboundEffects(UUID uuid) {
        return plugin.getConfigManager().getReplayMaxEffectsPerFrame() > 0
                && activeSessions.containsKey(uuid);
    }

    private void offerPendingEffect(UUID uuid, ReplayEffectSnapshot effect) {
        pendingEffects.computeIfAbsent(uuid, ignored -> new ArrayBlockingQueue<>(pendingEffectCapacity()))
                .offer(effect);
    }

    private int pendingEffectCapacity() {
        long configured = Math.max(1, plugin.getConfigManager().getReplayMaxEffectsPerFrame());
        return (int) Math.max(256L, Math.min(4096L, configured * 4L));
    }

    private ReplayEffectSnapshot particlePacketToEffect(Object packet) {
        if (!isParticlePacket(packet)) return null;

        List<Number> doubles = numericFields(packet, double.class);
        List<Number> floats = numericFields(packet, float.class);
        List<Number> ints = numericFields(packet, int.class);
        if (doubles.size() < 3 || floats.size() < 4 || ints.isEmpty()) return null;

        String particle = particleNameFromPacket(packet);
        if (particle == null) return null;

        return ReplayEffectSnapshot.particle("",
                doubles.get(0).doubleValue(), doubles.get(1).doubleValue(), doubles.get(2).doubleValue(),
                particle,
                Math.max(0, ints.get(0).intValue()),
                floats.get(0).doubleValue(), floats.get(1).doubleValue(), floats.get(2).doubleValue(),
                floats.get(3).doubleValue());
    }

    private boolean isParticlePacket(Object packet) {
        String simpleName = packet.getClass().getSimpleName();
        return "ClientboundLevelParticlesPacket".equals(simpleName)
                || "PacketPlayOutWorldParticles".equals(simpleName);
    }

    private List<Number> numericFields(Object target, Class<?> primitiveType) {
        List<Number> values = new ArrayList<>();
        for (Field field : allFields(target.getClass())) {
            if (field.getType() != primitiveType) continue;
            try {
                Object value = field.get(target);
                if (value instanceof Number number) values.add(number);
            } catch (Throwable ignored) {
            }
        }
        return values;
    }

    private List<Field> allFields(Class<?> type) {
        return reflectedFields.computeIfAbsent(type, this::discoverFields);
    }

    private List<Field> discoverFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return List.copyOf(fields);
    }

    private String particleNameFromPacket(Object packet) {
        Object particleOptions = firstFieldValueContaining(packet, "Particle");
        if (particleOptions == null) return null;

        String id = particleIdFrom(particleOptions);
        Object particleType = invokeAny(particleOptions, "getType", "type");
        if (id == null && particleType != null) {
            id = particleIdFrom(particleType);
        }
        if (id == null) return null;

        String candidate = normalizeParticleName(id);
        if (candidate == null) return null;
        return particleName(candidate, particleFallbacks(candidate));
    }

    private Object firstFieldValueContaining(Object target, String token) {
        for (Field field : allFields(target.getClass())) {
            String typeName = field.getType().getName();
            if (!typeName.contains(token) && !field.getType().getSimpleName().contains(token)) continue;
            try {
                Object value = field.get(target);
                if (value != null) return value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String particleIdFrom(Object target) {
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != String.class) continue;
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!name.contains("description") && !name.contains("id")) continue;
            try {
                String value = (String) method.invoke(target);
                if (looksLikeParticleId(value)) return value;
            } catch (Throwable ignored) {
            }
        }

        String value = String.valueOf(target);
        return looksLikeParticleId(value) ? value : null;
    }

    private Object invokeAny(Object target, String... names) {
        for (String name : names) {
            try {
                Method method = target.getClass().getMethod(name);
                if (method.getParameterCount() == 0) {
                    return method.invoke(target);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean looksLikeParticleId(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("particle.minecraft.") || lower.contains("minecraft:");
    }

    private String normalizeParticleName(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        int particlePrefix = value.lastIndexOf("particle.minecraft.");
        if (particlePrefix >= 0) {
            value = value.substring(particlePrefix + "particle.minecraft.".length());
        }
        int namespaced = value.lastIndexOf("minecraft:");
        if (namespaced >= 0) {
            value = value.substring(namespaced + "minecraft:".length());
        }

        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                clean.append(c);
            } else if (clean.length() > 0) {
                break;
            }
        }
        if (clean.isEmpty()) return null;
        return clean.toString().toUpperCase(Locale.ROOT);
    }

    private String particleName(String preferred, String... fallbacks) {
        String resolved = enumName(Particle.class, preferred, fallbacks);
        if (resolved == null) {
            plugin.debug("[Replay] Unsupported particle from packet: " + preferred);
        }
        return resolved;
    }

    private String soundName(String preferred, String... fallbacks) {
        String resolved = enumName(Sound.class, preferred, fallbacks);
        return resolved != null ? resolved : preferred;
    }

    @SafeVarargs
    private final <E extends Enum<E>> String enumName(Class<E> enumType, String preferred, String... fallbacks) {
        List<String> candidates = new ArrayList<>();
        if (preferred != null && !preferred.isBlank()) candidates.add(preferred);
        candidates.addAll(Arrays.asList(fallbacks));
        for (String candidate : candidates) {
            try {
                return Enum.valueOf(enumType, candidate).name();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String[] particleFallbacks(String candidate) {
        return switch (candidate) {
            case "FIREWORK" -> new String[]{"FIREWORKS_SPARK"};
            case "SPLASH" -> new String[]{"WATER_SPLASH", "INSTANT_EFFECT", "SPELL_INSTANT"};
            case "ITEM" -> new String[]{"ITEM_CRACK"};
            case "TOTEM_OF_UNDYING" -> new String[]{"TOTEM"};
            default -> new String[0];
        };
    }

    private void queueCombatEffectsNear(LivingEntity victim) {
        if (!hasDetailedCaptureInterest()) return;
        double radius = plugin.getConfigManager().getReplayEntityRadius();
        if (radius <= 0 || victim == null || victim.getWorld() == null) return;

        var hitLoc = victim.getLocation().add(0.0, Math.max(0.25, victim.getHeight() * 0.55), 0.0);
        double radiusSquared = radius * radius;
        String worldName = hitLoc.getWorld().getName();

        String damageParticle = particleName("DAMAGE_INDICATOR", "CRIT", "CRIT_MAGIC");
        ReplayEffectSnapshot damageEffect = damageParticle != null
                ? ReplayEffectSnapshot.particle(worldName,
                hitLoc.getX(), hitLoc.getY(), hitLoc.getZ(),
                damageParticle,
                8,
                0.25, 0.35, 0.25,
                0.05)
                : null;
        ReplayEffectSnapshot attackSound = ReplayEffectSnapshot.sound(worldName,
                hitLoc.getX(), hitLoc.getY(), hitLoc.getZ(),
                soundName("ENTITY_PLAYER_ATTACK_STRONG"), 0.85f, 1.0f);
        ReplayEffectSnapshot hurtSound = ReplayEffectSnapshot.sound(worldName,
                hitLoc.getX(), hitLoc.getY(), hitLoc.getZ(),
                victim instanceof Player ? soundName("ENTITY_PLAYER_HURT") : soundName("ENTITY_GENERIC_HURT"),
                0.75f, 1.0f);

        for (Player source : effectCaptureSources()) {
            if (!source.getWorld().equals(hitLoc.getWorld())) continue;
            if (source.getLocation().distanceSquared(hitLoc) > radiusSquared) continue;

            if (damageEffect != null) queueEffect(source, damageEffect);
            queueEffect(source, attackSound);
            queueEffect(source, hurtSound);
        }
    }

    private void queueEffectNear(LocationLike loc, ReplayEffectSnapshot effect) {
        if (loc == null || effect == null) return;
        double radius = plugin.getConfigManager().getReplayEntityRadius();
        if (radius <= 0) return;

        double radiusSquared = radius * radius;
        for (Player source : effectCaptureSources()) {
            if (source.getWorld() == null || !source.getWorld().getName().equals(loc.worldName())) continue;
            var sourceLocation = source.getLocation();
            double dx = sourceLocation.getX() - loc.x();
            double dy = sourceLocation.getY() - loc.y();
            double dz = sourceLocation.getZ() - loc.z();
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
            queueEffect(source, effect);
        }
    }

    private Iterable<? extends Player> effectCaptureSources() {
        if (activeSessions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Player> players = new ArrayList<>(activeSessions.size());
        for (UUID uuid : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean hasDetailedCaptureInterest() {
        return !activeSessions.isEmpty();
    }

    private void pruneOldReplays() {
        int maxFiles = plugin.getConfigManager().getReplayMaxFiles();
        if (maxFiles <= 0) return;

        File[] files = getSavedReplays();
        if (files.length <= maxFiles) return;

        for (int i = maxFiles; i < files.length; i++) {
            if (!files[i].delete()) {
                plugin.debug("[Replay] Could not delete old replay: " + files[i].getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (event.getFinalDamage() <= 0.0) return;
        if (!hasDetailedCaptureInterest()) return;
        recentlyDamagedActors.put(victim.getUniqueId(), System.currentTimeMillis() + 350L);
        queueCombatEffectsNear(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        RecordingSession session = activeSessions.get(event.getPlayer().getUniqueId());
        if (session != null) session.pendingSwing.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) return;
        Player player = event.getPlayer();
        if (player.isGliding()) {
            int power = item.getItemMeta() instanceof FireworkMeta meta
                    ? Math.max(1, meta.getPower()) : 1;
            markFireworkBoost(player, power);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireworkExplode(FireworkExplodeEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        if (!(event.getEntity().getShooter() instanceof Player owner)) return;
        var loc = event.getEntity().getLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        queueEffect(owner, ReplayEffectSnapshot.sound(worldName,
                loc.getX(), loc.getY(), loc.getZ(),
                soundName("ENTITY_FIREWORK_ROCKET_BLAST", "ENTITY_FIREWORK_BLAST"), 1.0f, 1.0f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        LivingEntity living = event.getEntity();
        if (living.getWorld() == null) return;
        var loc = living.getLocation().add(0.0, Math.max(0.5, living.getHeight() * 0.5), 0.0);
        String worldName = loc.getWorld().getName();
        LocationLike point = LocationLike.of(worldName, loc.getX(), loc.getY(), loc.getZ());
        queueEffectNear(point, ReplayEffectSnapshot.sound(worldName,
                loc.getX(), loc.getY(), loc.getZ(),
                soundName("ITEM_TOTEM_USE"), 1.0f, 1.0f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        Player player = event.getPlayer();
        var loc = player.getLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        String sound = event.getItem().getType().name().contains("POTION")
                ? "ENTITY_GENERIC_DRINK"
                : "ENTITY_GENERIC_EAT";
        queueEffect(player, ReplayEffectSnapshot.sound(worldName,
                loc.getX(), loc.getY() + 1.0, loc.getZ(),
                soundName(sound), 0.65f, 1.0f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        var loc = player.getEyeLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
        queueEffect(player, ReplayEffectSnapshot.sound(worldName,
                loc.getX(), loc.getY(), loc.getZ(),
                soundName("ENTITY_ARROW_SHOOT"), 0.9f, 1.0f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!plugin.getConfigManager().isReplayEnabled()) return;
        var loc = event.getEntity().getLocation();
        if (loc.getWorld() == null) return;
        String worldName = loc.getWorld().getName();
        LocationLike point = LocationLike.of(worldName, loc.getX(), loc.getY(), loc.getZ());
        queueEffectNear(point, ReplayEffectSnapshot.sound(worldName,
                loc.getX(), loc.getY(), loc.getZ(),
                soundName("ENTITY_SPLASH_POTION_BREAK", "ENTITY_SPLASH_POTION_THROW", "ENTITY_GENERIC_SPLASH"), 0.9f, 1.0f));
    }

    private record LocationLike(String worldName, double x, double y, double z) {
        static LocationLike of(String worldName, double x, double y, double z) {
            return new LocationLike(worldName, x, y, z);
        }
    }
}
