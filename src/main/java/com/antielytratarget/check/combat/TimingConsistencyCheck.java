package com.antielytratarget.check.combat;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

@CheckData(name = "TimingConsistency", configName = "timing_consistency",
        decay = 0.1, description = "Detects machine-regular hit timing")
public class TimingConsistencyCheck extends AbstractCheck {

    private double maxStdDevMs = 50.0;
    private int sampleSize = 6;

    public TimingConsistencyCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxStdDevMs = cfg("max_std_dev_ms", 50.0);
        sampleSize = cfgInt("sample_size", 6);
    }

    public boolean check(Player attacker, LivingEntity victim) {
        if (!enabled) return false;

        UUID uid = attacker.getUniqueId();
        UUID vid = victim.getUniqueId();
        long now = System.currentTimeMillis();

double maxSD = maxStdDevMs;
        double tps = plugin.getCurrentTps();
        if (tps > 0 && tps < 19.0) maxSD *= Math.max(0.5, tps / 20.0);

        UUID lv = aetPlayer.lastTargetUUID;
        long lh = aetPlayer.lastSameVictimHit;

        if (lv != null && lv.equals(vid) && lh > 0) {
            long iv = now - lh;
            if (iv >= 50 && iv <= 2000) {
                aetPlayer.timingIntervals.add(iv);
                if (aetPlayer.timingIntervals.size() > 20) aetPlayer.timingIntervals.remove(0);

                if (aetPlayer.timingIntervals.size() >= sampleSize) {
                    double sd = MathUtils.standardDeviation(aetPlayer.timingIntervals);
                    if (sd < maxSD) {
                        aetPlayer.timingIntervals.clear();
                        Player vp = (victim instanceof Player p) ? p : null;
                        return flagAndAlert(attacker, vp, sd);
                    }
                }
            }
        }

        if (lv == null || !lv.equals(vid)) {
            aetPlayer.timingIntervals.clear();
        }
        aetPlayer.lastSameVictimHit = now;

        reward();
        return false;
    }
}
