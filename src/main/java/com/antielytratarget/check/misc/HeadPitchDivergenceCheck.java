package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

@CheckData(name = "HeadPitchDivergence", configName = "head_pitch_divergence",
        decay = 0.1, description = "Detects pitch locked during vertical distance change")
public class HeadPitchDivergenceCheck extends AbstractCheck {

    private double minYDelta = 2.0;
    private double maxPitchDelta = 1.5;
    private static final int CONSEC = 3;

    public HeadPitchDivergenceCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        minYDelta = cfg("min_y_delta_blocks", 2.0);
        maxPitchDelta = cfg("max_pitch_delta_deg", 1.5);
    }

    public boolean check(Player attacker, LivingEntity victim) {
        if (!enabled) return false;

        java.util.UUID vid = victim.getUniqueId();
        if (aetPlayer.lastTargetUUID == null || !vid.equals(aetPlayer.lastTargetUUID)) {
            aetPlayer.pitchDivStreak = 0;
            reward();
            return false;
        }

        float pp = aetPlayer.lastHitPitch;
        double pyd = aetPlayer.lastHitYDist;

        double cyd = Math.abs(attacker.getLocation().getY() - victim.getLocation().getY());
        double pd = Math.abs(attacker.getLocation().getPitch() - pp);

        if (Math.abs(cyd - pyd) >= minYDelta && pd < maxPitchDelta) {
            aetPlayer.pitchDivStreak++;
            if (aetPlayer.pitchDivStreak >= CONSEC) {
                aetPlayer.pitchDivStreak = 0;
                Player vp = (victim instanceof Player p) ? p : null;
                return flagAndAlert(attacker, vp, pd);
            }
        } else {
            aetPlayer.pitchDivStreak = 0;
        }

        reward();
        return false;
    }
}
