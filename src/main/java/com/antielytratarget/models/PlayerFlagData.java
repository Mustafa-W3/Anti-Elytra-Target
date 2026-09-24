package com.antielytratarget.models;

import com.antielytratarget.utils.MathUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerFlagData {

    private final UUID   playerUUID;
    private final String playerName;

private final AtomicInteger totalFlags    = new AtomicInteger(0);
    private final AtomicInteger flagsInWindow = new AtomicInteger(0);
    private final AtomicLong    lastFlagTime  = new AtomicLong(0);

private final Queue<Float> yawHistory   = new LinkedList<>();
    private final Queue<Float> pitchHistory = new LinkedList<>();
    private float lastYaw;
    private float lastPitch;

private float   lastSnapYaw;
    private float   lastSnapPitch;
    private boolean snapSeeded = false;

private final Map<UUID, List<Double>> angleToTargetByVictim = new HashMap<>();

private int nursultanConsecutiveMatches;

private final AtomicInteger kickCount = new AtomicInteger(0);
    private final AtomicBoolean kickPending = new AtomicBoolean(false);

private final List<Long> fireworkIntervals  = new ArrayList<>();
    private long             lastFireworkTime   = 0L;

private final List<Double> strafeSyncSamples = new ArrayList<>();

private final List<Float> pitchDuringSamples = new ArrayList<>();

private final List<Double> rotationConsistencySamples = new ArrayList<>();

    public PlayerFlagData(UUID playerUUID, String playerName) {
        this.playerUUID = playerUUID;
        this.playerName = playerName;
    }

public synchronized FlagUpdate recordFlag(long now, long resetAfterMs) {
        long previous = lastFlagTime.get();
        boolean windowReset = now - previous > resetAfterMs;
        if (windowReset) flagsInWindow.set(0);

        int total = totalFlags.incrementAndGet();
        int window = flagsInWindow.incrementAndGet();
        long effectiveNow = Math.max(previous, now);
        lastFlagTime.set(effectiveNow);
        return new FlagUpdate(total, window, effectiveNow, windowReset);
    }

    public synchronized boolean resetWindowIfExpired(
            long now, long resetAfterMs) {
        if (now - lastFlagTime.get() <= resetAfterMs) return false;
        flagsInWindow.set(0);
        return true;
    }

    public int  getTotalFlags()    { return totalFlags.get(); }
    public int  getFlagsInWindow() { return flagsInWindow.get(); }
    public long getLastFlagTime()  { return lastFlagTime.get(); }

    public record FlagUpdate(
            int totalFlags,
            int flagsInWindow,
            long lastFlagTime,
            boolean windowReset) {
    }

public void addRotationSample(float yaw, float pitch, int maxSamples) {
        if (yawHistory.size() >= maxSamples) {
            yawHistory.poll();
            pitchHistory.poll();
        }
        yawHistory.offer(yaw);
        pitchHistory.offer(pitch);
        this.lastYaw   = yaw;
        this.lastPitch = pitch;
    }

    public Queue<Float> getYawHistory()   { return yawHistory; }
    public Queue<Float> getPitchHistory() { return pitchHistory; }
    public float        getLastYaw()      { return lastYaw; }
    public float        getLastPitch()    { return lastPitch; }

    public double calculateYawDeltaStdDev() {
        if (yawHistory.size() < 2) return Double.MAX_VALUE;
        Float[]  yaws   = yawHistory.toArray(new Float[0]);
        double[] deltas = new double[yaws.length - 1];
        for (int i = 1; i < yaws.length; i++) {
            deltas[i - 1] = MathUtils.yawDeltaDegrees(yaws[i - 1], yaws[i]);
        }
        return MathUtils.standardDeviation(deltas);
    }

public float   getLastSnapYaw()   { return lastSnapYaw; }
    public float   getLastSnapPitch() { return lastSnapPitch; }
    public boolean isSnapSeeded()     { return snapSeeded; }

    public void setLastSnapRotation(float yaw, float pitch) {
        this.lastSnapYaw   = yaw;
        this.lastSnapPitch = pitch;
        this.snapSeeded    = true;
    }

public void addAngleToTargetSample(UUID victimUUID, double angle) {
        angleToTargetByVictim
                .computeIfAbsent(victimUUID, k -> new ArrayList<>())
                .add(angle);
    }

    public List<Double> getAngleToTargetSamples(UUID victimUUID) {
        return angleToTargetByVictim.getOrDefault(victimUUID, Collections.emptyList());
    }

    public void clearAngleToTargetSamples(UUID victimUUID) {
        angleToTargetByVictim.remove(victimUUID);
    }

public void incrementNursultanMatches() { nursultanConsecutiveMatches++; }
    public void resetNursultanMatches()     { nursultanConsecutiveMatches = 0; }
    public int  getNursultanConsecutiveMatches() { return nursultanConsecutiveMatches; }

public int getKickCount() { return kickCount.get(); }

    public boolean tryReserveKick(int crossings) {
        return crossings > kickCount.get() && kickPending.compareAndSet(false, true);
    }

    public void completeKick(int crossings) {
        kickCount.accumulateAndGet(crossings, Math::max);
        kickPending.set(false);
    }

    public void releaseKick() {
        kickPending.set(false);
    }

public long recordFireworkUsage() {
        long now = System.currentTimeMillis();
        long interval = -1L;
        if (lastFireworkTime > 0) {
            interval = now - lastFireworkTime;
            fireworkIntervals.add(interval);
            if (fireworkIntervals.size() > 20) fireworkIntervals.remove(0);
        }
        lastFireworkTime = now;
        return interval;
    }

    public List<Long> getFireworkIntervals() { return Collections.unmodifiableList(fireworkIntervals); }
    public void clearFireworkIntervals()     { fireworkIntervals.clear(); }

public void addStrafeSyncSample(double deviation, int maxSamples) {
        strafeSyncSamples.add(deviation);
        while (strafeSyncSamples.size() > maxSamples) strafeSyncSamples.remove(0);
    }

    public List<Double> getStrafeSyncSamples() { return Collections.unmodifiableList(strafeSyncSamples); }
    public void clearStrafeSyncSamples()       { strafeSyncSamples.clear(); }

public void addPitchDuringSample(float pitch, int maxSamples) {
        pitchDuringSamples.add(pitch);
        while (pitchDuringSamples.size() > maxSamples) pitchDuringSamples.remove(0);
    }

    public List<Float> getPitchDuringSamples() { return Collections.unmodifiableList(pitchDuringSamples); }
    public void clearPitchDuringSamples()      { pitchDuringSamples.clear(); }

public void addRotationConsistencySample(double deltaAngle, int maxSamples) {
        rotationConsistencySamples.add(deltaAngle);
        while (rotationConsistencySamples.size() > maxSamples) rotationConsistencySamples.remove(0);
    }

    public List<Double> getRotationConsistencySamples() {
        return Collections.unmodifiableList(rotationConsistencySamples);
    }

    public void clearRotationConsistencySamples() { rotationConsistencySamples.clear(); }

public UUID   getPlayerUUID() { return playerUUID; }
    public String getPlayerName() { return playerName; }
}
