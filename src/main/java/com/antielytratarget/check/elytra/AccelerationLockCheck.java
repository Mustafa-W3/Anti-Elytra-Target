package com.antielytratarget.check.elytra;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.Player;

@CheckData(name = "AccelerationLock", configName = "acceleration_lock",
        decay = 0.05, description = "Detects speed-locked scripted gliding")
public class AccelerationLockCheck extends AbstractCheck {

    private double maxStdDev = 0.02;
    private int sampleSize = 30;
    private long maxTargetAgeMs = 2500L;

    public AccelerationLockCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxStdDev = cfg("max_std_dev", 0.02);
        sampleSize = cfgInt("sample_size", 30);
        maxTargetAgeMs = Math.max(0L, cfgInt("max_target_age_ms", 2500));
    }

    public void check(Player attacker) {
        if (!enabled) return;

        java.util.UUID lvU = aetPlayer.lastTargetUUID;
        Player lv = lvU != null ? plugin.getServer().getPlayer(lvU) : null;
        long now = System.currentTimeMillis();
        if (lv == null
                || (maxTargetAgeMs > 0 && (aetPlayer.lastHitTime <= 0 || now - aetPlayer.lastHitTime > maxTargetAgeMs))
                || lv.isInsideVehicle()
                || (aetPlayer.isOrWasRecentlyGliding() && lv.isGliding())) {
            aetPlayer.accelSamples.clear();
            return;
        }

org.bukkit.util.Vector vv = lv.getVelocity();
        if (vv.getX() * vv.getX() + vv.getZ() * vv.getZ() > 0.04 || Math.abs(vv.getY()) > 0.15) {
            aetPlayer.accelSamples.clear();
            return;
        }

        double cs = attacker.getVelocity().length();
        long lt = aetPlayer.lastSpeedTs;
        double ls = aetPlayer.lastSpeed;
        aetPlayer.lastSpeed = cs;
        aetPlayer.lastSpeedTs = now;

        if (lt == 0 || ls == 0) return;
        long tm = now - lt;
        if (tm < 10 || tm > 200) return;

        synchronized (aetPlayer.accelSamples) {
            aetPlayer.accelSamples.add(Math.abs(cs - ls));
            while (aetPlayer.accelSamples.size() > 50) aetPlayer.accelSamples.remove(0);

            if (aetPlayer.accelSamples.size() < sampleSize) return;

            double mean = MathUtils.average(aetPlayer.accelSamples, 1.0);
            if (mean < 0.004 || cs > 1.8) { aetPlayer.accelSamples.clear(); return; }

            double sd = MathUtils.standardDeviation(aetPlayer.accelSamples);

            if (sd < maxStdDev) {

                double[] deltas = null;
                if (aetPlayer.yawHistory.size() >= 5) {
                    synchronized (aetPlayer.yawHistory) {
                        int yawCount = aetPlayer.yawHistory.size();
                        int deltaCount = Math.min(yawCount - 1, 5);
                        if (deltaCount > 0) {
                            deltas = new double[deltaCount];
                            int start = yawCount - deltaCount;
                            for (int i = start; i < yawCount; i++) {
                                deltas[i - start] = MathUtils.yawDeltaDegrees(
                                        aetPlayer.yawHistory.get(i - 1),
                                        aetPlayer.yawHistory.get(i));
                            }
                        }
                    }
                    if (deltas != null) {
                        double yawStd = MathUtils.standardDeviation(deltas);
                        if (yawStd > 1.5) { aetPlayer.accelSamples.clear(); reward(); return; }
                    }
                }

                aetPlayer.accelSamples.clear();
                flagAndAlert(attacker, lv, sd);
            } else {
                reward();
            }
        }
    }
}
