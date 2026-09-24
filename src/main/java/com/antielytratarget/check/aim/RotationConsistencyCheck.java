package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

@CheckData(name = "RotationConsistency", configName = "rotation_consistency",
        decay = 0.1, description = "Detects unnaturally consistent aim angles")
public class RotationConsistencyCheck extends AbstractCheck {

    private double maxStdDev = 1.0;

private int sampleSize = 15;

    public RotationConsistencyCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxStdDev = cfg("max_std_dev", 1.0);
        sampleSize = cfgInt("sample_size", 10);
    }

    public boolean check(Player attacker, LivingEntity victim, double curAngle) {
        if (!enabled) return false;

        if (aetPlayer.lastHitAngle == 0) { reward(); return false; }

        double delta = Math.abs(curAngle - aetPlayer.lastHitAngle);
        synchronized (aetPlayer.rotConsistencySamples) {
            aetPlayer.rotConsistencySamples.add(delta);
            while (aetPlayer.rotConsistencySamples.size() > sampleSize) aetPlayer.rotConsistencySamples.remove(0);

            if (aetPlayer.rotConsistencySamples.size() < sampleSize) {
                reward();
                return false;
            }

            double std = MathUtils.standardDeviation(aetPlayer.rotConsistencySamples);
            aetPlayer.rotConsistencySamples.clear();

            if (std < maxStdDev) {
                Player vp = (victim instanceof Player p) ? p : null;
                return flagAndAlert(attacker, vp, std);
            }
        }

        reward();
        return false;
    }
}
