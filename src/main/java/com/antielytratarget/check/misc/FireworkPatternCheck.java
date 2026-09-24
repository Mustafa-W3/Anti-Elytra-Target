package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.Player;

import java.util.UUID;

@CheckData(name = "FireworkPattern", configName = "firework_pattern",
        decay = 0.05, description = "Detects scripted firework timing")
public class FireworkPatternCheck extends AbstractCheck {

    private int sampleSize = 8;
    private double maxIntervalStdDev = 100.0;

private static final long MIN_INTERVAL_MS = 500;
    private static final long MAX_INTERVAL_MS = 10_000;

    public FireworkPatternCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        sampleSize = cfgInt("sample_size", 8);
        maxIntervalStdDev = cfg("max_interval_std_dev", 100.0);
    }

    public void onFireworkExplode(Player owner) {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        if (aetPlayer.lastFireworkTime > 0) {
            long interval = now - aetPlayer.lastFireworkTime;

if (interval > MAX_INTERVAL_MS) {
                aetPlayer.fireworkIntervals.clear();
                aetPlayer.lastFireworkTime = now;
                return;
            }

if (interval < MIN_INTERVAL_MS) {
                aetPlayer.lastFireworkTime = now;
                return;
            }

            synchronized (aetPlayer.fireworkIntervals) {
                aetPlayer.fireworkIntervals.add(interval);
                if (aetPlayer.fireworkIntervals.size() > 30) aetPlayer.fireworkIntervals.remove(0);
            }
        }
        aetPlayer.lastFireworkTime = now;

        synchronized (aetPlayer.fireworkIntervals) {
            if (aetPlayer.fireworkIntervals.size() < sampleSize) return;

            double sd = MathUtils.standardDeviation(aetPlayer.fireworkIntervals);
            if (sd < maxIntervalStdDev) {
                aetPlayer.fireworkIntervals.clear();
                UUID lT = aetPlayer.lastTargetUUID;
                Player vp = lT != null ? plugin.getServer().getPlayer(lT) : null;
                flagAndAlert(owner, vp, sd);
            } else {
                reward();
            }
        }
    }
}
