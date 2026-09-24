package com.antielytratarget.check.combat;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.entity.Player;

@CheckData(name = "AttackRate", configName = "attack_rate",
        decay = 0.2, description = "Detects impossible attack speed")
public class AttackRateCheck extends AbstractCheck {

    private static final boolean TEMPORARILY_DISABLED = true;

    private int maxHitsPerSecond = 12;

    public AttackRateCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxHitsPerSecond = cfgInt("max_hits_per_second", 12);
    }

    public boolean check(Player attacker) {
        if (TEMPORARILY_DISABLED || !enabled) return false;

        long now = System.currentTimeMillis();
        long ws = aetPlayer.windowStart;
        int c = aetPlayer.hitCount;
        long elapsed = now - ws;

        if (elapsed >= 1000L) {
            aetPlayer.windowStart = now;
            aetPlayer.hitCount = 1;
            reward();
            return false;
        }

        c++;
        aetPlayer.hitCount = c;

        if (elapsed < 50) {
            reward();
            return false;
        }

        double limit = maxHitsPerSecond * (elapsed / 1000.0) + 2.0;
        if (c > limit && c >= 4) {
            aetPlayer.hitCount = 0;
            aetPlayer.windowStart = now;
            double rate = c / (elapsed / 1000.0);
            return flagAndAlert(attacker, null, rate);
        }

        reward();
        return false;
    }
}
