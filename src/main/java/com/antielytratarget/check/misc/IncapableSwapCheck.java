package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.check.PredictionCompleteCheck;
import com.antielytratarget.netty.SilentFireworkState;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

@CheckData(name = "IncapableSwap", configName = "incapable_swap",
        decay = 0.08,
        description = "Detects combat-advantage silent firework swaps")
public final class IncapableSwapCheck extends AbstractCheck
        implements PredictionCompleteCheck {

    private static final int MAX_PENDING_PATTERNS = 16;
    private static final int MAX_EARLY_CONFIRMATIONS = 16;
    private static final long PATTERN_TTL_NANOS = 2_000_000_000L;
    private static final long ATTACK_WINDOW_NANOS = 350_000_000L;
    private static final long ATTACK_EVENT_WINDOW_NANOS = 750_000_000L;
    private static final long DAMAGE_CONFIRM_WINDOW_NANOS = 750_000_000L;
    private static final int MAX_ATTACK_TICK_GAP = 3;
    private static final float FULL_ATTACK_STRENGTH = 0.90F;
    private static final double LEGACY_CONFIDENCE_MULTIPLIER = 0.50;

    private final ArrayDeque<TrackedPattern> pending = new ArrayDeque<>();
    private final Map<Long, ServerUseConfirmation> earlyConfirmations =
            new HashMap<>();
    private final ArrayDeque<Long> recentSequenceMillis = new ArrayDeque<>();

    private double evidenceBuffer;
    private long evidenceWindowStartNanos = Long.MIN_VALUE;

    private int maxTickSpan = 1;
    private long maxSequenceMillis = 175L;
    private long evidenceWindowMillis = 8_000L;
    private double evidenceThreshold = 3.0;

    public IncapableSwapCheck(AntiElytraTargetPlugin plugin,
                               AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxTickSpan = Math.max(0, cfgInt("max_tick_span", 1));
        maxSequenceMillis = Math.max(50L,
                cfgInt("max_sequence_ms", 175));
        evidenceWindowMillis = Math.max(1_000L,
                cfgInt("evidence_window_ms", 8_000));
        evidenceThreshold = Math.max(3.0,
                cfg("evidence_threshold", 3.0));
    }

    @Override
    public void onReload() {
        super.onReload();
        resetState();
    }

    public synchronized void onSwapPattern(
            SilentFireworkState.SwapPattern pattern,
            boolean tickReliable, boolean normalConfidence) {
        if (!enabled || pattern == null || !pattern.inventoryReliable()) return;
        if (!tickReliable || pattern.tickSpan() > maxTickSpan) return;
        if (pattern.elapsedMillis() > maxSequenceMillis) return;

        long now = System.nanoTime();
        cleanupExpired(now);
        TrackedPattern tracked = new TrackedPattern(pattern, normalConfidence);
        ServerUseConfirmation early =
                earlyConfirmations.remove(pattern.sequenceId());
        if (early != null) {
            tracked.serverUseConfirmed = true;
            tracked.glidingUseConfirmed = early.gliding();
        }
        pending.addLast(tracked);
        while (pending.size() > MAX_PENDING_PATTERNS) pending.removeFirst();
    }

    public synchronized void onServerFireworkUse(long sequenceId,
                                                  boolean gliding,
                                                  long confirmedNanos) {
        if (sequenceId < 0) return;
        cleanupExpired(confirmedNanos);
        for (TrackedPattern tracked : pending) {
            if (tracked.pattern.sequenceId() == sequenceId) {
                tracked.serverUseConfirmed = true;
                tracked.glidingUseConfirmed = gliding;
                return;
            }
        }

        earlyConfirmations.put(sequenceId,
                new ServerUseConfirmation(gliding, confirmedNanos));
        if (earlyConfirmations.size() > MAX_EARLY_CONFIRMATIONS) {
            Iterator<Long> iterator = earlyConfirmations.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    public synchronized void onAttackPacket(long attackTick,
                                             long attackNanos,
                                             int targetEntityId) {
        if (targetEntityId < 0) return;
        cleanupExpired(attackNanos);
        Iterator<TrackedPattern> iterator = pending.descendingIterator();
        while (iterator.hasNext()) {
            TrackedPattern tracked = iterator.next();
            if (tracked.attackNanos != Long.MIN_VALUE) continue;
            long nanosAfterSwap = attackNanos - tracked.pattern.switchBackNanos();
            long tickGap = attackTick - tracked.pattern.switchBackTick();
            if (nanosAfterSwap < 0 || nanosAfterSwap > ATTACK_WINDOW_NANOS) continue;
            if (tickGap < 0 || tickGap > MAX_ATTACK_TICK_GAP) continue;
            tracked.attackTick = attackTick;
            tracked.attackNanos = attackNanos;
            tracked.attackTargetEntityId = targetEntityId;
            return;
        }
    }

    public synchronized void onAttackCooldown(int targetEntityId,
                                              float cooledAttackStrength,
                                              long eventNanos) {
        if (targetEntityId < 0 || !Float.isFinite(cooledAttackStrength)) return;
        cleanupExpired(eventNanos);
        Iterator<TrackedPattern> iterator = pending.descendingIterator();
        while (iterator.hasNext()) {
            TrackedPattern tracked = iterator.next();
            if (tracked.attackNanos == Long.MIN_VALUE
                    || tracked.cooldownConfirmed) continue;
            long eventDelay = eventNanos - tracked.attackNanos;
            if (eventDelay < 0L || eventDelay > ATTACK_EVENT_WINDOW_NANOS) continue;
            if (tracked.attackTargetEntityId != targetEntityId) continue;

            tracked.cooldownConfirmed = true;
            tracked.cooledAttackStrength = cooledAttackStrength;
            return;
        }
    }

    public synchronized void onSuccessfulAttack(int targetEntityId,
                                                String heldItemKey,
                                                double rawDamage,
                                                double finalDamage,
                                                long damageNanos) {
        if (targetEntityId < 0 || !Double.isFinite(rawDamage)
                || !Double.isFinite(finalDamage) || finalDamage <= 0.0) return;
        cleanupExpired(damageNanos);
        Iterator<TrackedPattern> iterator = pending.descendingIterator();
        while (iterator.hasNext()) {
            TrackedPattern tracked = iterator.next();
            if (tracked.attackNanos == Long.MIN_VALUE
                    || !tracked.cooldownConfirmed
                    || tracked.damageConfirmed) continue;
            long eventDelay = damageNanos - tracked.attackNanos;
            if (eventDelay < 0 || eventDelay > DAMAGE_CONFIRM_WINDOW_NANOS) continue;
            if (tracked.attackTargetEntityId != targetEntityId) continue;
            if (!tracked.pattern.originalItemKey().equals(heldItemKey)) continue;

            tracked.damageConfirmed = true;
            tracked.rawDamage = rawDamage;
            tracked.finalDamage = finalDamage;
            return;
        }
    }

    @Override
    public void onPredictionComplete() {
        ArrayDeque<TrackedPattern> completed = new ArrayDeque<>();
        long now = System.nanoTime();
        synchronized (this) {
            cleanupExpired(now);
            Iterator<TrackedPattern> iterator = pending.iterator();
            while (iterator.hasNext()) {
                TrackedPattern tracked = iterator.next();
                if (tracked.isComplete()) {
                    completed.addLast(tracked);
                    iterator.remove();
                }
            }
        }

        if (completed.isEmpty()) {
            expireEvidenceWindow(now);
            return;
        }

        Player player = aetPlayer.player;
        if (!canCheck(player) || isBedrockExempt()
                || player.isInsideVehicle()) {
            resetEvidence();
            return;
        }

        for (TrackedPattern tracked : completed) {
            SilentFireworkState.SwapPattern pattern = tracked.pattern;
            double weight = evidenceWeight(pattern, tracked.normalConfidence);
            addEvidence(weight, now);
            recordSequenceTiming(pattern.elapsedMillis());

            if (plugin.isDebugEnabled()) {
                plugin.debug("[IncapableSwap] " + player.getName()
                        + " path=" + pattern.path()
                        + " slot=" + pattern.originalSlot() + "->"
                        + pattern.rocketSlot() + "->" + pattern.originalSlot()
                        + " ticks=" + pattern.switchToRocketTick() + "/"
                        + pattern.useRocketTick() + "/"
                        + pattern.switchBackTick() + "/" + tracked.attackTick
                        + " swapMs=" + pattern.elapsedMillis()
                        + " attackMs=" + ((tracked.attackNanos
                        - pattern.switchBackNanos()) / 1_000_000L)
                        + " targetId=" + tracked.attackTargetEntityId
                        + " cooldown=" + String.format("%.3f",
                        tracked.cooledAttackStrength)
                        + " damage=" + String.format("%.2f", tracked.rawDamage)
                        + "/" + String.format("%.2f", tracked.finalDamage)
                        + " confidence="
                        + (tracked.normalConfidence ? "normal" : "low")
                        + " timingSpreadMs=" + timingSpreadMillis()
                        + " evidence=" + String.format("%.2f", evidenceBuffer));
            }

            if (evidenceBuffer >= evidenceThreshold) {
                double attackDelayMillis = (tracked.attackNanos
                        - pattern.switchBackNanos()) / 1_000_000.0;
                resetEvidence();
                flagAndAlert(1.0, player, null, attackDelayMillis);
            }
        }
    }

    public synchronized void resetState() {
        pending.clear();
        earlyConfirmations.clear();
        recentSequenceMillis.clear();
        resetEvidence();
    }

    private double evidenceWeight(SilentFireworkState.SwapPattern pattern,
                                  boolean normalConfidence) {
        double weight = pattern.sameClientTick() ? 1.0 : 0.75;
        return normalConfidence ? weight
                : weight * LEGACY_CONFIDENCE_MULTIPLIER;
    }

    private void addEvidence(double weight, long nowNanos) {
        long windowNanos = evidenceWindowMillis * 1_000_000L;
        if (evidenceWindowStartNanos == Long.MIN_VALUE
                || nowNanos - evidenceWindowStartNanos < 0
                || nowNanos - evidenceWindowStartNanos > windowNanos) {
            evidenceBuffer = 0.0;
            evidenceWindowStartNanos = nowNanos;
        }
        evidenceBuffer += weight;
    }

    private void expireEvidenceWindow(long nowNanos) {
        if (evidenceWindowStartNanos == Long.MIN_VALUE) return;
        if (nowNanos - evidenceWindowStartNanos
                > evidenceWindowMillis * 1_000_000L) {
            resetEvidence();
        }
    }

    private void resetEvidence() {
        evidenceBuffer = 0.0;
        evidenceWindowStartNanos = Long.MIN_VALUE;
    }

    private void cleanupExpired(long nowNanos) {
        pending.removeIf(tracked -> nowNanos - tracked.pattern.switchBackNanos()
                > PATTERN_TTL_NANOS);
        earlyConfirmations.entrySet().removeIf(entry ->
                nowNanos - entry.getValue().confirmedNanos()
                        > PATTERN_TTL_NANOS);
    }

    private void recordSequenceTiming(long elapsedMillis) {
        recentSequenceMillis.addLast(elapsedMillis);
        while (recentSequenceMillis.size() > 8) recentSequenceMillis.removeFirst();
    }

    private long timingSpreadMillis() {
        if (recentSequenceMillis.size() < 3) return -1L;
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        for (long value : recentSequenceMillis) {
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        return maximum - minimum;
    }

    private static final class TrackedPattern {
        private final SilentFireworkState.SwapPattern pattern;
        private final boolean normalConfidence;
        private boolean serverUseConfirmed;
        private boolean glidingUseConfirmed;
        private long attackTick = Long.MIN_VALUE;
        private long attackNanos = Long.MIN_VALUE;
        private int attackTargetEntityId = -1;
        private boolean cooldownConfirmed;
        private float cooledAttackStrength;
        private boolean damageConfirmed;
        private double rawDamage;
        private double finalDamage;

        private TrackedPattern(SilentFireworkState.SwapPattern pattern,
                               boolean normalConfidence) {
            this.pattern = pattern;
            this.normalConfidence = normalConfidence;
        }

        private boolean isComplete() {
            return serverUseConfirmed && glidingUseConfirmed
                    && attackNanos != Long.MIN_VALUE
                    && cooldownConfirmed
                    && cooledAttackStrength > FULL_ATTACK_STRENGTH
                    && damageConfirmed;
        }
    }

    private record ServerUseConfirmation(boolean gliding,
                                         long confirmedNanos) {
    }
}
