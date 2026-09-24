package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.Player;

@CheckData(name = "SmoothAim", configName = "smooth_aim",
        decay = 0.1, description = "Detects aim-assist smooth rotation")
public class SmoothAimCheck extends AbstractCheck {

    private double maxStdDev = 1.2;
    private int sampleSize = 30;

    public SmoothAimCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxStdDev = cfg("max_std_dev", 1.2);
        sampleSize = cfgInt("sample_size", 30);
    }

    public boolean check(Player attacker, Player victim) {
        if (!enabled) return false;

        int required = (int) (sampleSize * 1.5);
        if (aetPlayer.yawHistory.size() < required) {
            reward();
            return false;
        }

        double std = calculateYawDeltaStdDev();
        double ang = victim != null ? MathUtils.getAngleToPlayer(attacker, victim) : 0;

        if (plugin.isDebugEnabled()) {
            plugin.debug("[SmoothAim] " + attacker.getName() + " std="
                    + String.format("%.4f", std) + " ang=" + String.format("%.2f", ang));
        }

        if (std < maxStdDev && (victim == null || ang < 2.0)) {
            return flagAndAlert(attacker, victim, std);
        }

        reward();
        return false;
    }

    private double calculateYawDeltaStdDev() {
        if (aetPlayer.yawHistory.size() < 2) return Double.MAX_VALUE;
        Float[] yaws;
        synchronized (aetPlayer.yawHistory) {
            yaws = aetPlayer.yawHistory.toArray(new Float[0]);
        }
        double[] deltas = new double[yaws.length - 1];
        for (int i = 1; i < yaws.length; i++) {
            deltas[i - 1] = MathUtils.yawDeltaDegrees(yaws[i - 1], yaws[i]);
        }
        return MathUtils.standardDeviation(deltas);
    }
}
