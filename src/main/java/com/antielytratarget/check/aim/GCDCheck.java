package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.entity.Player;

@CheckData(name = "GCD", configName = "gcd",
        decay = 0.05, description = "Detects aim-assist via mouse sensitivity GCD")
public class GCDCheck extends AbstractCheck {

private int consecutiveFracTicks = 0;
    private static final int CONSEC_REQUIRED = 3;

    public GCDCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

public boolean check(Player attacker) {
        if (!enabled) return false;
        if (!aetPlayer.aimProcessor.isCalibrated()) {
            consecutiveFracTicks = 0;
            reward();
            return false;
        }

        double dX = aetPlayer.aimProcessor.deltaDotsX;
        double dY = aetPlayer.aimProcessor.deltaDotsY;

double fracX = Math.abs(dX - Math.round(dX));
        double fracY = Math.abs(dY - Math.round(dY));

if (fracX > 0.15 && fracX < 0.85 && dX > 0.5) {
            consecutiveFracTicks++;
            if (consecutiveFracTicks >= CONSEC_REQUIRED) {
                consecutiveFracTicks = CONSEC_REQUIRED;
                return flagAndAlert(0.5, attacker, null, fracX);
            }
            return false;
        }

        consecutiveFracTicks = 0;
        reward();
        return false;
    }
}
