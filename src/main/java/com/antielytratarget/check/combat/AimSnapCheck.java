package com.antielytratarget.check.combat;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.AimStatistics;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@CheckData(name = "AimSnap", configName = "aim_snap",
        decay = 0.15, description = "Detects MX-style aim snap/randomizer patterns")
public class AimSnapCheck extends AbstractCheck {

    private final float[] buffer = new float[16];
    private final List<int[]> rotations = new ArrayList<>(10);
    private final List<double[]> kireikoGeneric = new ArrayList<>(7);

    private long lastAttack;
    private float spikeVlLimit = 7.0f;
    private float randomizerVlLimit = 2.5f;

    public AimSnapCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        spikeVlLimit = (float) cfg("spike_vl_limit", 7.0);
        randomizerVlLimit = (float) cfg("randomizer_vl_limit", 2.5);
    }

    public boolean check(Player attacker, LivingEntity victim, double angle) {
        if (!enabled) return false;
        lastAttack = System.currentTimeMillis();
        reward();
        return false;
    }

    public void tick(float deltaYaw, float deltaPitch) {
        if (!enabled) return;
        long since = System.currentTimeMillis() - Math.max(lastAttack, aetPlayer.lastAttackTime);
        if (since < 0 || since > 3500L) return;

        double gcdValue = AimStatistics.getGCDValue(0.5d) * 3.0d;
        rotations.add(new int[]{
                (int) (deltaYaw / gcdValue),
                (int) (deltaPitch / gcdValue)
        });

        if (rotations.size() >= 10) {
            checkSpikes();
        }
    }

    private void checkSpikes() {
        List<Integer> gcdYaw = new ArrayList<>();
        List<Integer> gcdPitch = new ArrayList<>();
        for (int[] vec : rotations) {
            gcdYaw.add(vec[0]);
            gcdPitch.add(vec[1]);
        }
        rotations.clear();
        if (gcdYaw.isEmpty() || aetPlayer.mxCinematic) return;

        double[] sample = new double[]{
                AimStatistics.getKireikoGeneric(gcdYaw),
                AimStatistics.getKireikoGeneric(gcdPitch)
        };
        kireikoGeneric.add(sample);

        if (kireikoGeneric.size() >= 7) {
            List<Double> x = new ArrayList<>();
            for (double[] vec : kireikoGeneric) {
                x.add(vec[0]);
            }

            double xDev = AimStatistics.getStandardDeviation(x);
            double max = AimStatistics.getMax(x);
            if (xDev > 5.0 && xDev < 22.0 && max < 50.0) {
                increaseBuffer(5, AimStatistics.getAverage(x) < 6.0 ? 0.0f : (xDev < 10.0 ? 1.5f : 1.0f));
                if (buffer[5] >= spikeVlLimit) {
                    flagAndAlert(1.0, aetPlayer.player, null, xDev);
                    buffer[5] = spikeVlLimit - 1.0f;
                }
            } else {
                increaseBuffer(5, (xDev < 40.0 || max < 70.0) ? -0.4f : -0.8f);
            }
            kireikoGeneric.clear();
        }

        double devX = AimStatistics.getVariance(gcdYaw);
        double devY = AimStatistics.getVariance(gcdPitch);
        double min = Math.min(devX, devY);
        double max = Math.max(devX, devY);
        if (min < 0.09 && max > 35.0 && AimStatistics.getMin(gcdPitch) != 0.0
                && aetPlayer.mxSensitivity.calculateSensitivity() > 50) {
            increaseBuffer(4, 1.0f);
            if (buffer[4] > randomizerVlLimit) {
                flagAndAlert(3.5, aetPlayer.player, null, max);
                buffer[4] = randomizerVlLimit - 1.0f;
            }
        } else {
            increaseBuffer(4, -0.4f);
        }
    }

    private void increaseBuffer(int index, float value) {
        buffer[index] = Math.max(0.0f, buffer[index] + value);
    }
}
