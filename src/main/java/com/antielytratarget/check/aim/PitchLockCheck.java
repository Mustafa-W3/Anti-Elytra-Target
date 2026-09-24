package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

@CheckData(name = "PitchLock", configName = "pitch_lock",
        decay = 0.1, description = "Detects frozen pitch during vertical distance changes")
public class PitchLockCheck extends AbstractCheck {

    private double maxVariance = 4.0;
    private int sampleSize = 15;

    public PitchLockCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxVariance = cfg("max_variance", 4.0);
        sampleSize = cfgInt("sample_size", 15);
    }

    public boolean check(Player attacker, LivingEntity victim) {
        if (!enabled) return false;

if (Math.abs(attacker.getLocation().getY() - victim.getLocation().getY()) < 4.0) {
            reward();
            return false;
        }

        synchronized (aetPlayer.pitchDuringSamples) {
            aetPlayer.pitchDuringSamples.add(attacker.getLocation().getPitch());
            while (aetPlayer.pitchDuringSamples.size() > sampleSize) aetPlayer.pitchDuringSamples.remove(0);

            if (aetPlayer.pitchDuringSamples.size() < sampleSize) {
                reward();
                return false;
            }

            double sd = MathUtils.standardDeviation(aetPlayer.pitchDuringSamples);
            aetPlayer.pitchDuringSamples.clear();

            if (sd < maxVariance) {
                Player vp = (victim instanceof Player p) ? p : null;
                return flagAndAlert(attacker, vp, sd);
            }
        }

        reward();
        return false;
    }
}
