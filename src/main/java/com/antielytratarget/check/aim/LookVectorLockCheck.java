package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.Player;

import java.util.List;

@CheckData(name = "LookVectorLock", configName = "look_vector_lock",
        decay = 0.1, description = "Detects lock-on aiming at stationary targets")
public class LookVectorLockCheck extends AbstractCheck {

    private int sampleSize = 16;
    private double maxVariance = 2.0;

    public LookVectorLockCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        sampleSize = cfgInt("sample_size", 16);
        maxVariance = cfg("max_variance", 2.0);
    }

    public boolean check(Player attacker, Player victim) {
        if (!enabled) return false;
        if (victim.isGliding()) { reward(); return false; }

        List<Double> s = aetPlayer.angleToTargetByVictim
                .computeIfAbsent(victim.getUniqueId(), k -> new java.util.ArrayList<>());

        double angle = MathUtils.getAngleToPlayer(attacker, victim);
        s.add(angle);
        while (s.size() > sampleSize) s.remove(0);

        if (s.size() < sampleSize) { reward(); return false; }

        double mean = MathUtils.average(s, 90.0);
        double std = MathUtils.standardDeviation(s);

        aetPlayer.angleToTargetByVictim.remove(victim.getUniqueId());

if (std < maxVariance && mean < 3.5) {
            return flagAndAlert(attacker, victim, std);
        }

        reward();
        return false;
    }

    public void addSample(java.util.UUID victimUUID, double angle) {
        aetPlayer.angleToTargetByVictim
                .computeIfAbsent(victimUUID, k -> new java.util.ArrayList<>())
                .add(angle);
    }
}
