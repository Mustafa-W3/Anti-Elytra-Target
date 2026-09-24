package com.antielytratarget.check.combat;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.AimStatistics;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@CheckData(name = "AngleDeviation", configName = "angle_deviation",
        decay = 0.1,
        description = "Detects sustained bidirectional aim deviations during elytra combat")
public class AngleDeviationCheck extends AbstractCheck {

    private static final long ATTACK_WINDOW_MS = 3_500L;
    private static final long MAX_SAMPLE_GAP_MS = 500L;
    private static final double OUTLIER_Z_THRESHOLD = 2.0;
    private static final double MIN_OUTLIER_ANGLE = 10.0;
    private static final double MAX_OUTLIER_ANGLE = 55.0;

    private final List<float[]> rawRotations = new ArrayList<>(32);

    private long lastAttack;
    private long lastSampleAt;
    private UUID activeTarget;
    private String activeVictimName;
    private float deviationBuffer;
    private int sampleSize = 25;
    private float zfactorVlLimit = 7.0f;

    public AngleDeviationCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        sampleSize = Math.max(15, cfgInt("sample_size", 25));
        zfactorVlLimit = Math.max(3.0f, (float) cfg("zfactor_vl_limit", 7.0));
        resetAnalysis();
    }

    public boolean check(Player attacker, LivingEntity victim, double angle) {
        if (!canCheck(attacker)) {
            resetAnalysis();
            return false;
        }

        long now = System.currentTimeMillis();
        UUID targetId = victim.getUniqueId();
        boolean newCombatSession = lastAttack == 0L || now - lastAttack > ATTACK_WINDOW_MS;
        boolean targetChanged = activeTarget != null && !activeTarget.equals(targetId);
        if (newCombatSession || targetChanged) {
            resetAnalysis();
        }

        activeTarget = targetId;
        activeVictimName = victim.getName();
        lastAttack = now;
        reward();
        return false;
    }

    public void tick(float deltaYaw, float deltaPitch) {
        long now = System.currentTimeMillis();
        long sinceAttack = now - lastAttack;

        if (!canCheck(aetPlayer.player)
                || lastAttack == 0L
                || activeTarget == null
                || sinceAttack < 0L
                || sinceAttack > ATTACK_WINDOW_MS
                || !aetPlayer.player.isGliding()
                || aetPlayer.mxCinematic
                || aetPlayer.isPostToggleOff()
                || aetPlayer.isPostToggleOn()) {
            resetAnalysis();
            return;
        }

        if (!Float.isFinite(deltaYaw) || !Float.isFinite(deltaPitch)) {
            resetAnalysis();
            return;
        }

        if (lastSampleAt > 0L && now - lastSampleAt > MAX_SAMPLE_GAP_MS) {
            resetSampleWindow();
            deviationBuffer = 0.0f;
        }
        lastSampleAt = now;

        rawRotations.add(new float[]{deltaYaw, deltaPitch});
        if (rawRotations.size() >= sampleSize) {
            checkRaw();
        }
    }

    private void checkRaw() {
        List<Float> yaw = new ArrayList<>();
        for (float[] rotation : rawRotations) {
            yaw.add(rotation[0]);
        }
        rawRotations.clear();

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(
                        yaw, OUTLIER_Z_THRESHOLD, MIN_OUTLIER_ANGLE, MAX_OUTLIER_ANGLE);

        if (evidence.suspicious()) {
            deviationBuffer = Math.min(zfactorVlLimit + 1.0f, deviationBuffer + 1.0f);
            if (deviationBuffer >= zfactorVlLimit) {
                flagAndAlertNamedTarget(
                        1.0, aetPlayer.player, activeVictimName, evidence.maxAbsoluteOutlier());
                deviationBuffer = Math.max(0.0f, zfactorVlLimit - 2.0f);
            }
        } else {
            deviationBuffer = Math.max(0.0f, deviationBuffer - 1.0f);
        }
    }

    public void resetAnalysis() {
        resetSampleWindow();
        deviationBuffer = 0.0f;
        lastAttack = 0L;
        activeTarget = null;
        activeVictimName = null;
    }

    private void resetSampleWindow() {
        rawRotations.clear();
        lastSampleAt = 0L;
    }
}
