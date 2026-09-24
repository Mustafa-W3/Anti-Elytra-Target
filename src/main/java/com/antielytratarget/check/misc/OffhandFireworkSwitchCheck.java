package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.entity.Player;

@CheckData(name = "OffhandFireworkSwitch", configName = "offhand_firework_switch",
        decay = 0.1, description = "Detects scripted offhand firework switching")
public class OffhandFireworkSwitchCheck extends AbstractCheck {

    private static final long SEQ_MS = 150L;
    private static final long HIT_MS = 100L;
    private static final int STREAK = 2;

    public OffhandFireworkSwitchCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    public boolean checkOnHit(Player attacker) {
        if (!enabled) return false;
        if (aetPlayer.ofwStep != 3) return false;

        long ft = aetPlayer.ofwFireTime;
        if (ft == 0) { resetOfw(); return false; }

        long haf = System.currentTimeMillis() - ft;
        if (haf > HIT_MS) { resetOfw(); aetPlayer.ofwStreak = 0; return false; }

        aetPlayer.ofwStreak++;
        resetOfw();
        if (aetPlayer.ofwStreak >= STREAK) {
            aetPlayer.ofwStreak = 0;
            return flagAndAlert(attacker, null, (double) haf);
        }

        reward();
        return false;
    }

    public void onSwapToFirework() {
        aetPlayer.ofwStep = 1;
        aetPlayer.ofwStepStart = System.currentTimeMillis();
    }

    public void onFireworkUse() {
        if (aetPlayer.ofwStep != 1) { resetOfw(); return; }
        long s = aetPlayer.ofwStepStart;
        if (s > 0 && System.currentTimeMillis() - s <= SEQ_MS) {
            aetPlayer.ofwStep = 2;
            aetPlayer.ofwFireTime = System.currentTimeMillis();
        } else {
            resetOfw();
        }
    }

    public void onSwapFromFirework() {
        if (aetPlayer.ofwStep != 2) { resetOfw(); return; }
        long s = aetPlayer.ofwStepStart;
        if (s > 0 && System.currentTimeMillis() - s <= SEQ_MS) {
            aetPlayer.ofwStep = 3;
        } else {
            resetOfw();
        }
    }

    private void resetOfw() {
        aetPlayer.ofwStep = 0;
        aetPlayer.ofwStepStart = 0;
        aetPlayer.ofwFireTime = 0;
    }
}
