package com.antielytratarget.player;

import com.antielytratarget.check.CheckManager;
import com.antielytratarget.utils.EvictingList;
import com.antielytratarget.utils.BedrockPlayerResolver;
import com.antielytratarget.utils.GrimAimProcessor;
import com.antielytratarget.utils.MXSensitivityProcessor;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AETPlayer {

    public final UUID uuid;
    public final Player player;
    private volatile BedrockPlayerResolver bedrockPlayerResolver;

public volatile CheckManager checkManager;

public volatile long lastGlidingTime = 0;
    private volatile boolean currentlyGliding;
    private volatile boolean insideVehicle;
    public volatile long lastToggleOffTime = 0;
    public volatile long lastToggleOnTime = 0;
    public volatile long lastFwBoostTime = 0;
    public volatile long lastRealBoostTime = 0;
    public volatile long lastDamageTime = 0;

    public static final long RECENTLY_GLIDING_TTL_MS = 2_500L;
    public static final long POST_TOGGLE_SUPPRESS_MS = 350L;
    public static final long POST_TOGGLE_ON_SUPPRESS_MS = 600L;
    public static final long FW_BOOST_GRACE_MS = 2_500L;

public volatile UUID lastTargetUUID;
    public volatile long lastHitTime = 0;
    public volatile float lastHitYaw;
    public volatile float lastHitPitch;
    public volatile double lastHitYDist;
    public volatile double lastHitAngle;

public final GrimAimProcessor aimProcessor = new GrimAimProcessor();

public final List<Integer> sensitivityHistory = Collections.synchronizedList(new EvictingList<>(14));
    public final MXSensitivityProcessor mxSensitivity = new MXSensitivityProcessor(sensitivityHistory);

public volatile long lastAttackTime = 0;
    public volatile float prevTickYaw = Float.NaN;
    public volatile float prevTickPitch = Float.NaN;
    public volatile boolean mxCinematic = false;

public volatile int approachTrackingTicks = 0;
    public static final int APPROACH_TRACKING_THRESHOLD = 8;
    public static final float APPROACH_TRACKING_MAX_DELTA = 6.0f;

public volatile float aimSnapLastYaw;
    public volatile float aimSnapLastPitch;
    public volatile org.bukkit.Location aimSnapLastVicLoc;
    public volatile boolean aimSnapSeeded = false;

public final List<Float> yawHistory = Collections.synchronizedList(new ArrayList<>());
    public final List<Float> pitchHistory = Collections.synchronizedList(new ArrayList<>());

public final List<Double> angleSamples = Collections.synchronizedList(new ArrayList<>());
    public final List<Double> strafeSyncSamples = Collections.synchronizedList(new ArrayList<>());
    public final List<Float> pitchDuringSamples = Collections.synchronizedList(new ArrayList<>());
    public final List<Double> rotConsistencySamples = Collections.synchronizedList(new ArrayList<>());
    public final List<Long> timingIntervals = Collections.synchronizedList(new ArrayList<>());
    public final List<Double> accelSamples = Collections.synchronizedList(new ArrayList<>());
    public final List<Double> physicsOffsets = Collections.synchronizedList(new ArrayList<>());
    public final List<Long> fireworkIntervals = Collections.synchronizedList(new ArrayList<>());

public final Map<UUID, List<Double>> angleToTargetByVictim = new ConcurrentHashMap<>();

    public volatile int angleStreak = 0;
    public volatile int nearZeroAngleStreak = 0;
    public volatile int nursultanMatches = 0;
    public volatile int physicsStreak = 0;
    public volatile int pitchDivStreak = 0;

public volatile float lastCombatYaw;
    public volatile float lastCombatPitch;

public volatile int hitCount = 0;
    public volatile long windowStart = 0;

public volatile double[] lastKnownVel;
    public volatile double lastSpeed;
    public volatile long lastSpeedTs;
    public volatile double lastDistToVictim;

public volatile long lastSameVictimHit;

public final Deque<float[]> lookHistory = new ArrayDeque<>();
    public final Deque<org.bukkit.Location> posHistory = new ArrayDeque<>();

public volatile long lastFireworkTime = 0;

public volatile int ofwStep = 0;
    public volatile long ofwStepStart = 0;
    public volatile long ofwFireTime = 0;
    public volatile int ofwStreak = 0;

public volatile boolean nearbyCache = false;
    public volatile long nearbyCacheTime = 0;

    public AETPlayer(Player player) {
        this.uuid = player.getUniqueId();
        this.player = player;
    }

    public boolean isBedrockPlayer() {
        BedrockPlayerResolver resolver = bedrockPlayerResolver;
        if (resolver == null) {
            synchronized (this) {
                resolver = bedrockPlayerResolver;
                if (resolver == null) {
                    resolver = new BedrockPlayerResolver(uuid);
                    bedrockPlayerResolver = resolver;
                }
            }
        }
        return resolver.isBedrockPlayer();
    }

public boolean isOrWasRecentlyGliding() {
        if (currentlyGliding) {
            lastGlidingTime = System.currentTimeMillis();
            return true;
        }
        return lastGlidingTime > 0 && (System.currentTimeMillis() - lastGlidingTime) <= RECENTLY_GLIDING_TTL_MS;
    }

    public void setCurrentlyGliding(boolean gliding) {
        currentlyGliding = gliding;
        if (gliding) markRecentlyGliding();
    }

    public boolean isInsideVehicle() {
        return insideVehicle;
    }

    public void setInsideVehicle(boolean insideVehicle) {
        this.insideVehicle = insideVehicle;
    }

    public void markRecentlyGliding() {
        lastGlidingTime = System.currentTimeMillis();
    }

    public boolean isPostToggleOff() {
        return lastToggleOffTime > 0 && (System.currentTimeMillis() - lastToggleOffTime) < POST_TOGGLE_SUPPRESS_MS;
    }

    public boolean isPostToggleOn() {
        return lastToggleOnTime > 0 && (System.currentTimeMillis() - lastToggleOnTime) < POST_TOGGLE_ON_SUPPRESS_MS;
    }

    public boolean isFwBoosted() {
        return lastFwBoostTime > 0 && (System.currentTimeMillis() - lastFwBoostTime) <= FW_BOOST_GRACE_MS;
    }

    public void confirmRealBoost() {
        lastRealBoostTime = System.currentTimeMillis();
    }

    public void addAngleSample(double angle, int maxSize) {
        angleSamples.add(angle);
        while (angleSamples.size() > maxSize) angleSamples.remove(0);
    }

    public void addRotationSample(float yaw, float pitch, int maxSize) {
        yawHistory.add(yaw);
        pitchHistory.add(pitch);
        while (yawHistory.size() > maxSize) { yawHistory.remove(0); pitchHistory.remove(0); }
    }
}
