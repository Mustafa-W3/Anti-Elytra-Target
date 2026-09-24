package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.AimStatistics;

import java.util.*;

@CheckData(name = "AimComplex", configName = "aim_complex",
        decay = 0.05, description = "MX-Project complex aim analysis")
public class AimComplexCheck extends AbstractCheck {

private final float[] buffer;

private final List<int[]> rotations2;

private final List<double[]> kireikoGeneric;

private final List<float[]> rawRotations;

    private double oldShannonYaw, oldShannonPitch;

private int entropyVlLimit = 30;
    private float distinctVlLimit = 4.0f;
    private float randomizerVlLimit = 2.5f;

    public AimComplexCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
        this.rotations2 = new ArrayList<>(10);
        this.rawRotations = new ArrayList<>(10);
        this.kireikoGeneric = new ArrayList<>(7);
        this.buffer = new float[16];
        this.oldShannonYaw = 0;
        this.oldShannonPitch = 0;
    }

    @Override
    protected void loadConfig() {
        entropyVlLimit = cfgInt("entropy_vl_limit", 30);
        distinctVlLimit = (float) cfg("distinct_vl_limit", 4.0);
        randomizerVlLimit = (float) cfg("randomizer_vl_limit", 2.5);
    }

public void tick(float deltaYaw, float deltaPitch) {
        if (!enabled) return;

        this.rawRotations.add(new float[]{deltaYaw, deltaPitch});

        double gcdValue = AimStatistics.getGCDValue(0.5d) * 3;
        this.rotations2.add(new int[]{
                (int) (deltaYaw / gcdValue),
                (int) (deltaPitch / gcdValue)
        });

        if (this.rotations2.size() >= 10) {
            this.checkSpikes();
        }
        if (this.rawRotations.size() >= 10) {
            this.checkRaw();
        }
    }

private void checkRaw() {
        if (aetPlayer.mxCinematic) {
            this.rawRotations.clear();
            return;
        }

        final int sens = aetPlayer.mxSensitivity.calculateSensitivity();
        final int sensTemp = aetPlayer.mxSensitivity.totalSensitivityClient;
        final List<Float> x = new ArrayList<>(), y = new ArrayList<>();
        for (float[] vec2 : this.rawRotations) {
            x.add(vec2[0]);
            y.add(vec2[1]);
        }

        final int disX = AimStatistics.getDistinct(x);
        final double shannonYaw = AimStatistics.getShannonEntropy(x);
        final double shannonPitch = AimStatistics.getShannonEntropy(y);
        final boolean valid = sens >= 75 && sens <= 150 && sensTemp >= 75 && sensTemp < 150;

if (valid && getDifference(shannonYaw, oldShannonYaw) < 1e-5
                && getDifference(shannonPitch, oldShannonPitch) < 1e-5) {
            increaseBuffer(11, 1.0f);
            if (this.buffer[11] > entropyVlLimit) {
                if (flagAndAlert(1.0, aetPlayer.player, null, shannonYaw)) {

                }
                this.buffer[11] = entropyVlLimit - 1;
            }
        } else {
            this.buffer[11] = 0f;
        }

if (valid && getDifference(shannonYaw, shannonPitch) < 1e-5) {
            increaseBuffer(12, 1.0f);
            if (this.buffer[12] > entropyVlLimit) {
                if (flagAndAlert(1.0, aetPlayer.player, null, shannonYaw)) {

                }
                this.buffer[12] = entropyVlLimit - 1;
            }
        } else {
            this.buffer[12] = 0f;
        }

if (disX < 5 && Math.abs(AimStatistics.getAverage(x)) > 3.5) {
            increaseBuffer(9, 1.7f);
            if (this.buffer[9] >= distinctVlLimit) {
                flagAndAlert(0.5, aetPlayer.player, null, disX);
                increaseBuffer(9, -0.5f);
            }
        } else {
            increaseBuffer(9, -0.35f);
        }

        this.oldShannonYaw = shannonYaw;
        this.oldShannonPitch = shannonPitch;
        this.rawRotations.clear();
    }

private void checkSpikes() {
        List<Integer> gcdYaw = new ArrayList<>(), gcdPitch = new ArrayList<>();
        for (int[] vec2i : this.rotations2) {
            gcdYaw.add(vec2i[0]);
            gcdPitch.add(vec2i[1]);
        }
        this.rotations2.clear();
        if (gcdYaw.isEmpty()) return;

double[] kireikoGenericVec = new double[]{
                AimStatistics.getKireikoGeneric(gcdYaw),
                AimStatistics.getKireikoGeneric(gcdPitch)
        };
        this.kireikoGeneric.add(kireikoGenericVec);

        if (this.kireikoGeneric.size() >= 7) {
            final List<Double> kx = new ArrayList<>(), ky = new ArrayList<>();
            for (double[] vec2 : this.kireikoGeneric) {
                kx.add(vec2[0]);
                ky.add(vec2[1]);
            }
            double xDev = AimStatistics.getStandardDeviation(kx);
            AimStatistics.Pair<Double, Double> xSpikes =
                    new AimStatistics.Pair<>(AimStatistics.getMin(kx), AimStatistics.getMax(kx));

            if (xDev > 5 && xDev < 22 && xSpikes.getY() < 50) {
                increaseBuffer(5, (AimStatistics.getAverage(kx) < 6.0) ? 0 : (xDev < 10) ? 1.5f : 1.0f);
                if (this.buffer[5] >= 7.0f) {
                    this.buffer[5] = 6.0f;
                }
            } else {
                increaseBuffer(5, (xDev < 40 || xSpikes.getY() < 70) ? -0.4f : -0.8f);
            }
            this.kireikoGeneric.clear();
        }

double devX = AimStatistics.getVariance(gcdYaw);
        double devY = AimStatistics.getVariance(gcdPitch);
        double min = Math.min(devX, devY);
        double max = Math.max(devX, devY);
        if ((min < 0.09 && max > 35 && AimStatistics.getMin(gcdPitch) != 0.0)
                && aetPlayer.mxSensitivity.calculateSensitivity() > 50) {
            increaseBuffer(4, 1.0f);
            if (this.buffer[4] > randomizerVlLimit) {
                flagAndAlert(3.5, aetPlayer.player, null, max);
                this.buffer[4] = randomizerVlLimit - 1;
            }
        } else {
            increaseBuffer(4, -0.4f);
        }
    }

private static double getDifference(double a, double b) {
        return Math.abs(Math.abs(a) - Math.abs(b));
    }

    private void increaseBuffer(int index, float v) {
        float r = this.buffer[index] + v;
        this.buffer[index] = Math.max(0, r);
    }
}
