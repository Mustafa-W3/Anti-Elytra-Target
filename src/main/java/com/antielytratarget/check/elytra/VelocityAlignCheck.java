package com.antielytratarget.check.elytra;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@CheckData(name = "VelocityAlign", configName = "velocity_align",
        decay = 0.1, description = "Detects velocity vector locked to target")
public class VelocityAlignCheck extends AbstractCheck {

    private double matchThreshold = 8.0;
    private int consecutiveMatches = 12;
    private double maxVelMag = 1.8;

    public VelocityAlignCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        matchThreshold = cfg("match_threshold", 8.0);
        consecutiveMatches = cfgInt("consecutive_matches", 12);
        maxVelMag = cfg("max_velocity_magnitude", 1.8);
    }

    public boolean check(Player attacker, LivingEntity victim) {
        if (!enabled) return false;

        Vector vel = attacker.getVelocity();
        if (vel.lengthSquared() < 0.005 || aetPlayer.isFwBoosted()) { reward(); return false; }
        if (vel.lengthSquared() > maxVelMag * maxVelMag) { reward(); return false; }

        Vector toV = victim.getLocation().add(0, victim.getHeight() / 2.0, 0)
                .toVector().subtract(attacker.getLocation().toVector());
        if (toV.lengthSquared() < 0.001) { reward(); return false; }

        double angD = MathUtils.angleBetween(vel, toV);

        if (angD <= matchThreshold) {
            aetPlayer.nursultanMatches++;
            int thr = consecutiveMatches;

            if (victim instanceof Player vp && vp.isGliding()) thr *= 2;

            if (aetPlayer.nursultanMatches >= thr) {
                aetPlayer.nursultanMatches = 0;
                Player vp = (victim instanceof Player p) ? p : null;
                return flagAndAlert(attacker, vp, angD);
            }
        } else {
            aetPlayer.nursultanMatches = 0;
        }

        reward();
        return false;
    }
}
