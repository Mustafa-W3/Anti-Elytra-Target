package com.antielytratarget.check.aim;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.AimStatistics;
import com.antielytratarget.utils.EvictingList;

import java.util.*;

@CheckData(name = "AimHeuristic", configName = "aim_heuristic",
        decay = 0.03, description = "MX-Project heuristic aim analysis (7 components)")
public class AimMXHeuristicCheck extends AbstractCheck {

private final List<double[]> basicRawRotations;
    private float basicVl = 0, basicVlL2 = 0;
    private int basicStreak = 0;
    private String basicReason = "";

private float constLastDeltaYaw = 0, constLastDeltaPitch = 0;
    private float constBuffer1 = 0, constBuffer2 = 0, constBuffer3 = 0;

private int invalidBuffer = 0;
    private static final float INVALID_PITCH = 90f + 1e-6f;

private float inconsistLastDeltaYaw = 0, inconsistLastDeltaPitch = 0;
    private final List<Float> inconsistSamplesYaw = new ArrayList<>();
    private final List<Float> inconsistSamplesPitch = new ArrayList<>();
    private float inconsistBuffer = 0;

private float patternOldDeltaYaw;
    private float patternOldDeltaPitch;
    private final List<float[]> patternSample = new ArrayList<>();
    private static final int PATTERN_LENGTH = 3;
    private static final int PATTERN_SAMPLE_SIZE = 100;
    private static final int MIN_START_INDEX_GAP = PATTERN_LENGTH;
    private float patternBuffer = 0;

private boolean factorLastIsNoRotation = false;
    private double factorLastHash = 0;
    private float factorBuffer = 0;
    private int factorTicksToReset = 0;
    private long factorLastInvalidTime = 0;
    private final List<Double> factorStack = new EvictingList<>(3);
    private static final long FACTOR_CHAIN_MS = 1_250L;
    private static final float FACTOR_DECAY = 0.25f;
    private static final double FACTOR_MANUAL_GLIDE_FLICK_DEG = 120.0;
    private static final double FACTOR_ACTIVE_GLIDE_SPEED = 0.35;

private final List<Double> smoothStack = new ArrayList<>();

private int basicVlLimit = 400;
    private int basicL2VlLimit = 400;
    private float patternBufLimit = 2.5f;
    private float factorBufLimit = 3.0f;

private long lastResetTime = 0;

    public AimMXHeuristicCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
        this.basicRawRotations = new ArrayList<>(10);
    }

    @Override
    protected void loadConfig() {
        basicVlLimit = cfgInt("basic_vl_limit", 400);
        basicL2VlLimit = cfgInt("basic_l2_vl_limit", 400);
        patternBufLimit = (float) cfg("pattern_buffer_limit", 2.5);
        factorBufLimit = (float) cfg("factor_buffer_limit", 3.0);
    }

public void resetAllBuffers() {
        basicRawRotations.clear();
        basicVl = 0;
        basicVlL2 = 0;
        basicStreak = 0;
        constBuffer1 = 0;
        constBuffer2 = 0;
        constBuffer3 = 0;
        constLastDeltaYaw = 0;
        constLastDeltaPitch = 0;
        invalidBuffer = 0;
        inconsistSamplesYaw.clear();
        inconsistSamplesPitch.clear();
        inconsistBuffer = 0;
        inconsistLastDeltaYaw = 0;
        inconsistLastDeltaPitch = 0;
        patternSample.clear();
        patternOldDeltaYaw = 0;
        patternOldDeltaPitch = 0;
        patternBuffer = 0;
        factorStack.clear();
        factorBuffer = 0;
        factorTicksToReset = 0;
        factorLastInvalidTime = 0;
        factorLastIsNoRotation = false;
        factorLastHash = 0;
        smoothStack.clear();
        lastResetTime = System.currentTimeMillis();
    }

public void tick(float deltaYaw, float deltaPitch,
                     float absDeltaYaw, float absDeltaPitch, float toPitch) {
        if (!enabled) return;
        if (aetPlayer.mxCinematic) return;

if (aetPlayer.isPostToggleOff() || aetPlayer.isPostToggleOn()) return;

if (System.currentTimeMillis() - lastResetTime < 500) return;

if (System.currentTimeMillis() - aetPlayer.lastDamageTime < 300) return;

boolean approachTracking = aetPlayer.approachTrackingTicks
                >= com.antielytratarget.player.AETPlayer.APPROACH_TRACKING_THRESHOLD;

boolean hasRotation = absDeltaYaw > 0 || absDeltaPitch > 0;

processFactorComponent(absDeltaYaw, hasRotation);

        if (!hasRotation) return;

if (!approachTracking) {
            processBasicComponent(deltaYaw, deltaPitch, absDeltaYaw, absDeltaPitch, toPitch);
        } else {

            this.basicRawRotations.clear();
        }

processConstantComponent(absDeltaYaw, absDeltaPitch);

processInvalidComponent(deltaYaw, deltaPitch, absDeltaYaw, absDeltaPitch, toPitch);

if (!approachTracking) {
            processInconsistentComponent(absDeltaYaw, absDeltaPitch);
        }

if (!approachTracking) {
            processPatternComponent(deltaYaw, deltaPitch);
        } else {

            patternOldDeltaYaw = deltaYaw;
            patternOldDeltaPitch = deltaPitch;
        }

if (!approachTracking) {
            processSmoothComponent(deltaYaw, deltaPitch, absDeltaYaw, absDeltaPitch);
        } else {
            smoothStack.clear();
        }
    }

private void processBasicComponent(float deltaYaw, float deltaPitch,
                                       float absDeltaYaw, float absDeltaPitch, float toPitch) {

this.basicRawRotations.add(new double[]{deltaYaw + aetPlayer.prevTickYaw, deltaPitch + aetPlayer.prevTickPitch});

        if (this.basicRawRotations.size() >= 10) checkBasicAim();
    }

    private void checkBasicAim() {
        final List<double[]> rotations = new ArrayList<>(this.basicRawRotations);
        Set<Double> yaws = new HashSet<>();
        double oldYaw = rotations.get(0)[0];
        for (double[] r : rotations) {
            yaws.add(Math.abs(r[0] - oldYaw));
            oldYaw = r[0];
        }

        double oldYawResult = rotations.get(0)[0];
        double oldPitchResult = rotations.get(0)[1];
        double oldYawChange = Math.abs(rotations.get(0)[0] - oldYawResult);
        double yawChangeFirst = Math.abs(rotations.get(0)[0] - rotations.get(1)[0]);
        int machineKnownMovement = 0, constantRotations = 0,
                aggressivePatternI = 0, aggressivePatternD = 0,
                aggressivePatternI2 = 0, aggressivePatternD2 = 0,
                robotizedAmount = 0, aggressiveAim = 0, infinitives = 0;

List<Double> yawChanges = new ArrayList<>();

        for (double[] rotation : rotations) {
            double yawChange = Math.abs(rotation[0] - oldYawResult);
            double robotized = Math.abs(yawChange - yawChangeFirst);
            double diffBetweenYawChanges = yawChange - oldYawChange;

            yawChanges.add(yawChange);

            if (robotized < 2 && yawChange > 2.5) robotizedAmount++;
            if (robotized < 0.99 && yawChange > 4) machineKnownMovement++;
            if (robotized < 0.02 && yawChange > 3) constantRotations++;
            if (robotized < 2 && yawChange > 3) aggressiveAim++;

            double interpolation = AimStatistics.scaleVal(yawChange / robotized, 2);
            if (Double.isInfinite(interpolation) && yawChange > 0) {
                infinitives++;
                if (infinitives > 1 && yawChange < 0.4) infinitives--;
            }

            if ((diffBetweenYawChanges > 0.01 && diffBetweenYawChanges < 2)) aggressivePatternI++;
            if ((diffBetweenYawChanges < -0.01 && diffBetweenYawChanges > -2)) aggressivePatternD++;
            if (diffBetweenYawChanges > 2) aggressivePatternI2++;
            if (diffBetweenYawChanges < -2) aggressivePatternD2++;

            oldYawResult = rotation[0];
            oldPitchResult = rotation[1];
            oldYawChange = yawChange;
        }

double yawChangeVariance = AimStatistics.getVariance(yawChanges);

        final int sens = aetPlayer.mxSensitivity.calculateSensitivity();

if (sens > 65) {

if (robotizedAmount > 9 && yawChangeVariance < 0.3) addBasicVl("heuristic(sync)", 125);
            if (aggressiveAim > 9 && yawChangeVariance < 0.5) addBasicVl("heuristic(aggressive)", 50);
            if (machineKnownMovement > 8 && yawChangeVariance < 0.2) addBasicVl("heuristic(aim)", 100);
            if (constantRotations > 5) addBasicVl("heuristic(constant)", 65);
        } else if (sens > 0) {

            if (machineKnownMovement > 9 && yawChangeVariance < 0.2) addBasicVl("heuristic(aim)", 100);
            if (constantRotations > 7) addBasicVl("heuristic(constant)", 65);
        }

if (infinitives > 1 && Math.abs(AimStatistics.getAverage(yaws)) > 3.2) {
            addBasicVlL2("heuristic(interpolation)", 55);
        }

if (aggressivePatternI > 5 && aggressivePatternD > 5) {
            addBasicVlL2("pattern(random)", 25);
        }
        if (aggressivePatternI2 > 4 && aggressivePatternD2 > 4
                && (aggressivePatternI2 + aggressivePatternD2) > 9) {
            basicStreak++;
            if (basicStreak > 3) addBasicVl("pattern(snap)", 55);
        } else basicStreak = 0;

if (this.basicVl > basicVlLimit) {
            flagAndAlert(1.0, aetPlayer.player, null, basicVl);
            this.basicVl = 360;
        }
        if (this.basicVlL2 > basicL2VlLimit) {
            flagAndAlert(1.0, aetPlayer.player, null, basicVlL2);
            this.basicVlL2 -= 65;
        }

if (this.basicVl > 0) this.basicVl -= 8;
        if (this.basicVl > 400) this.basicVl -= 15;
        if (this.basicVlL2 > 0) this.basicVlL2 -= 8;
        if (this.basicVlL2 > 380) this.basicVlL2 -= 15;

        this.basicRawRotations.clear();
    }

    private void addBasicVl(String reason, float vl) {
        this.basicReason = reason;
        this.basicVl += vl;
        if (plugin.isDebugEnabled()) {
            plugin.debug("[AimHeuristic/Basic] " + aetPlayer.player.getName()
                    + " " + reason + " vl=" + basicVl + " (+" + vl + ")");
        }
    }

    private void addBasicVlL2(String reason, float vl) {
        this.basicVlL2 += vl;
        if (plugin.isDebugEnabled()) {
            plugin.debug("[AimHeuristic/Interpolation] " + aetPlayer.player.getName()
                    + " " + reason + " vl=" + basicVlL2 + " (+" + vl + ")");
        }
    }

private void processConstantComponent(float absDeltaYaw, float absDeltaPitch) {
        final int sens = aetPlayer.mxSensitivity.calculateSensitivity();
        final int sensClient = aetPlayer.mxSensitivity.totalSensitivityClient;

if (sens == -1) {
            constLastDeltaYaw = absDeltaYaw;
            constLastDeltaPitch = absDeltaPitch;
            return;
        }

        final boolean sensitivityTooLow = (sens < 50 && sens > -1) || sensClient < 50;

        final double divisorYaw = AimStatistics.getGcd(
                (long) (absDeltaYaw * AimStatistics.EXPANDER),
                (long) (constLastDeltaYaw * AimStatistics.EXPANDER));
        final double divisorPitch = AimStatistics.getGcd(
                (long) (absDeltaPitch * AimStatistics.EXPANDER),
                (long) (constLastDeltaPitch * AimStatistics.EXPANDER));

        final double constantYaw = divisorYaw / AimStatistics.EXPANDER;
        final double constantPitch = divisorPitch / AimStatistics.EXPANDER;

        final float MAX_DELTA = 20.0f;
        final float MIN_DELTA = 0.1f;

{
            final long expandedPitch = (long) (AimStatistics.EXPANDER * absDeltaPitch);
            final long expandedLastPitch = (long) (AimStatistics.EXPANDER * constLastDeltaPitch);
            final long gcd = AimStatistics.getGcd(expandedPitch, expandedLastPitch);
            final boolean validAngles = absDeltaYaw > 0.25f && absDeltaPitch > 0.25f
                    && absDeltaPitch < MAX_DELTA && absDeltaYaw < MAX_DELTA;
            final boolean invalid = gcd < 131072L;
            if (invalid && validAngles && !sensitivityTooLow) {
                constBuffer1 = Math.min(constBuffer1 + 1, 200);
                if (constBuffer1 > 20) {
                    flagAndAlert(0.5, aetPlayer.player, null, constBuffer1);
                    constBuffer1 = 8;
                }
            } else if (constBuffer1 > 0) {
                constBuffer1 -= 3f;
            }
        }

if (constantYaw > 0 && constantPitch > 0) {
            final double currentX = absDeltaYaw / constantYaw;
            final double currentY = absDeltaPitch / constantPitch;
            final double previousX = constLastDeltaYaw / constantYaw;
            final double previousY = constLastDeltaPitch / constantPitch;

            final boolean validDelta = absDeltaYaw > MIN_DELTA && absDeltaPitch > MIN_DELTA
                    && absDeltaYaw < MAX_DELTA && absDeltaPitch < MAX_DELTA;

            if (validDelta && previousX != 0 && previousY != 0) {
                final double moduloX = currentX % previousX;
                final double moduloY = currentY % previousY;
                final double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
                final double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);
                final boolean invalidX = moduloX > 60.0 && floorModuloX > 0.1;
                final boolean invalidY = moduloY > 60.0 && floorModuloY > 0.1;

                if (invalidX && invalidY && !sensitivityTooLow) {
                    constBuffer2 = Math.min(constBuffer2 + 1, 200);
                    if (constBuffer2 > 15) {
                        flagAndAlert(0.5, aetPlayer.player, null, constBuffer2);
                        constBuffer2 = 8;
                    }
                } else if (constBuffer2 > 0) {
                    constBuffer2 -= 3f;
                }

if (invalidX && invalidY && !sensitivityTooLow) {
                    constBuffer3 = Math.max(constBuffer3 + ((absDeltaPitch < 1 || absDeltaPitch > 13) ? 2f : 1), 0);
                    float limit = 15;
                    if (constBuffer3 > ((sens < 70) ? limit + 2 : limit)) {
                        flagAndAlert(0.5, aetPlayer.player, null, constBuffer3);
                        constBuffer3 = 0;
                    }
                } else if (constBuffer3 > 0) {
                    constBuffer3 -= 3f;
                }
            }
        }

        this.constLastDeltaYaw = absDeltaYaw;
        this.constLastDeltaPitch = absDeltaPitch;
    }

private void processInvalidComponent(float deltaYaw, float deltaPitch,
                                         float absDeltaYaw, float absDeltaPitch, float toPitch) {

boolean straightFlight = absDeltaPitch < 0.01f && absDeltaYaw > 2.0f
                && aetPlayer.isOrWasRecentlyGliding();

        if (!straightFlight
                && AimStatistics.isExponentiallySmall(absDeltaPitch)
                && absDeltaPitch > 0.0 && absDeltaYaw > 0.5f) {

invalidBuffer += 10;
            if (invalidBuffer > 100) {
                flagAndAlert(10.0, aetPlayer.player, null, deltaPitch);
            }
        } else {

            invalidBuffer -= 2;
            if (invalidBuffer < 0) invalidBuffer = 0;
        }

        if (toPitch > INVALID_PITCH) {
            flagAndAlert(10.0, aetPlayer.player, null, toPitch);
        }
    }

private void processInconsistentComponent(float absDeltaYaw, float absDeltaPitch) {
        int sens = aetPlayer.mxSensitivity.calculateSensitivity();
        int sensClient = aetPlayer.mxSensitivity.totalSensitivityClient;
        boolean invalidSensitivity = sens < 75 || sens > 175 || sensClient < 75 || sensClient > 170;
        if (invalidSensitivity) return;

        float differenceYaw = Math.abs(absDeltaYaw - inconsistLastDeltaYaw);
        float differencePitch = Math.abs(absDeltaPitch - inconsistLastDeltaPitch);
        float joltX = Math.abs(absDeltaYaw - differenceYaw);
        float joltY = Math.abs(absDeltaPitch - differencePitch);

        inconsistSamplesYaw.add((float) AimStatistics.roundToPlace(joltX, 2));
        inconsistSamplesPitch.add((float) AimStatistics.roundToPlace(joltY, 2));

        if (inconsistSamplesYaw.size() + inconsistSamplesPitch.size() >= 60) {
            if (!(joltX == 0.0 || joltY == 0.0)) {
                AimStatistics.Pair<List<Double>, List<Double>> outliersYaw = AimStatistics.getOutliers(inconsistSamplesYaw);
                AimStatistics.Pair<List<Double>, List<Double>> outliersPitch = AimStatistics.getOutliers(inconsistSamplesPitch);

                int duplicatesX = AimStatistics.getDuplicates(inconsistSamplesYaw);
                int duplicatesY = AimStatistics.getDuplicates(inconsistSamplesPitch);
                int duplicatesSum = duplicatesX + duplicatesY;
                int outliersX = outliersYaw.getX().size() + outliersYaw.getY().size();
                int outliersY = outliersPitch.getX().size() + outliersPitch.getY().size();

                if (plugin.isDebugEnabled()) {
                    plugin.debug("[AimHeuristic/Inconsistent] " + aetPlayer.player.getName()
                            + " outliers=" + outliersX + "," + outliersY
                            + " dups=" + duplicatesSum);
                }

if ((duplicatesSum <= 2 && duplicatesSum >= 1
                        && outliersX < 8 && outliersY < 5)
                        && inconsistBuffer++ >= 5) {
                    flagAndAlert(0.5, aetPlayer.player, null, outliersX);
                    inconsistBuffer = 2;
                } else if (((outliersX == 0 || outliersY == 0) && (outliersX > 2 || outliersY > 2)
                        && duplicatesSum <= 2 && duplicatesSum >= 1)
                        && inconsistBuffer++ >= 5) {
                    flagAndAlert(0.5, aetPlayer.player, null, outliersX);
                    inconsistBuffer = 2;
                } else {

                    inconsistBuffer -= 1.0f;
                    if (inconsistBuffer < 0) inconsistBuffer = 0;
                }
            }
            inconsistSamplesYaw.clear();
            inconsistSamplesPitch.clear();
        }

        inconsistLastDeltaYaw = absDeltaYaw;
        inconsistLastDeltaPitch = absDeltaPitch;
    }

private void processPatternComponent(float deltaYaw, float deltaPitch) {
        float yawFactor = deltaYaw - patternOldDeltaYaw;
        float pitchFactor = deltaPitch - patternOldDeltaPitch;
        float[] vec = new float[]{yawFactor, pitchFactor};
        patternSample.add(vec);

        if (patternSample.size() >= PATTERN_SAMPLE_SIZE) {
            boolean flagged = false;

List<Float> rawPatterns = new ArrayList<>(), filteredPatterns = new ArrayList<>();
            for (int i = 1; i < PATTERN_SAMPLE_SIZE; i++) {
                if (Math.abs(patternSample.get(i)[0]) > 1.0) {
                    rawPatterns.add(Math.abs(patternSample.get(i)[0] - patternSample.get(i - 1)[1]));
                }
            }
            for (float x : rawPatterns) if (x < 1e-4) filteredPatterns.add(x);

if (filteredPatterns.size() > 8) {
                flagged = true;
                if (patternBuffer++ >= patternBufLimit) {
                    flagAndAlert(2.0, aetPlayer.player, null, filteredPatterns.size());
                    patternBuffer -= 1;
                }
            }

if (!flagged) {
                int currentSampleSize = patternSample.size();
                List<float[]> patterns = new ArrayList<>();
                final float EPSILON = 0.005f;

                for (int i = 0; i <= currentSampleSize - PATTERN_LENGTH; ++i) {
                    for (int j = i + MIN_START_INDEX_GAP; j <= currentSampleSize - PATTERN_LENGTH; ++j) {
                        boolean allMatch = true;
                        for (int k = 0; k < PATTERN_LENGTH; ++k) {
                            float[] first = patternSample.get(i + k);
                            float[] second = patternSample.get(j + k);
                            if (Math.abs(first[0] - second[0]) > EPSILON
                                    || Math.abs(first[1] - second[1]) > EPSILON) {
                                allMatch = false;
                                break;
                            }
                        }
                        if (allMatch) {
                            float[] first = patternSample.get(i);
                            boolean exists = false;
                            for (float[] p : patterns) {
                                if (Math.abs(p[0] - first[0]) < EPSILON
                                        && Math.abs(p[1] - first[1]) < EPSILON) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) patterns.add(first);
                            break;
                        }
                    }
                }

                for (float[] vec2f : patterns) {
                    float x = Math.abs(vec2f[0]);
                    float y = Math.abs(vec2f[1]);
                    if ((x > 1.0 || y > 1.0) && (x > 0.26 && y > 0.26)) {
                        flagged = true;
                        if (patternBuffer++ >= patternBufLimit) {
                            flagAndAlert(2.0, aetPlayer.player, null, x);
                            patternBuffer -= 1f;
                        }
                        break;
                    }
                }
            }
            if (!flagged) patternBuffer = Math.max(0, patternBuffer - 0.5f);
            patternSample.clear();
        }

        patternOldDeltaYaw = deltaYaw;
        patternOldDeltaPitch = deltaPitch;
    }

private void processFactorComponent(float absDeltaYaw, boolean hasRotation) {

if (aetPlayer.isPostToggleOff() || aetPlayer.isPostToggleOn()) {
            factorStack.clear();
            return;
        }

        if (!hasRotation) {
            if (!factorLastIsNoRotation) factorStack.add(0.0);
            checkFactor();
            factorLastIsNoRotation = true;
        } else {
            factorStack.add(AimStatistics.scaleVal(absDeltaYaw, 2));
            checkFactor();
            factorLastIsNoRotation = false;
        }
    }

    private void checkFactor() {
        if (factorStack.size() != 3) return;
        double hash = factorStack.get(0) + factorStack.get(1) + factorStack.get(2);
        if (hash == factorLastHash) return;

        double centre = factorStack.get(1);

boolean hugeRotation = centre > 50;

        if (hugeRotation && centre != 360.0f) {

double compare = 0.5;
            boolean invalid = (factorStack.get(0) < compare && factorStack.get(2) < compare)
                    || (factorStack.get(0) > 55 && factorStack.get(1) < 2 && factorStack.get(2) > 55)
                    || (AimStatistics.getMax(factorStack) > 70
                    && AimStatistics.getMin(factorStack) < compare
                    && AimStatistics.getDistinct(factorStack) != 3);

            if (invalid) {
                if (isLikelyManualGlideFlick(centre)) {
                    decayFactorBuffer();
                    factorLastHash = hash;
                    return;
                }

                long now = System.currentTimeMillis();
                if (factorLastInvalidTime == 0 || now - factorLastInvalidTime > FACTOR_CHAIN_MS) {
                    factorBuffer = 0;
                }

                float localVl = factorVlFor(centre);
                factorBuffer += localVl;
                factorLastInvalidTime = now;
                factorTicksToReset = 0;
                if (plugin.isDebugEnabled()) {
                    plugin.debug("[AimHeuristic/Factor] " + aetPlayer.player.getName()
                            + " centre=" + centre + " buf=" + factorBuffer);
                }
                if (factorBuffer >= factorBufLimit) {
                    flagAndAlert(2.5, aetPlayer.player, null, centre);
                    factorBuffer = Math.max(0, factorBufLimit - 1);
                }
            } else {
                decayFactorBuffer();
            }
        } else {

            factorTicksToReset++;
            if (factorTicksToReset >= 1200) {
                factorTicksToReset = 0;
                factorBuffer = 0;
                factorLastInvalidTime = 0;
            } else {
                decayFactorBuffer();
            }
        }
        factorLastHash = hash;
    }

    private boolean isLikelyManualGlideFlick(double centre) {
        if (centre < FACTOR_MANUAL_GLIDE_FLICK_DEG) return false;
        if (!aetPlayer.isOrWasRecentlyGliding()) return false;
        if (!aetPlayer.player.isGliding()
                && aetPlayer.player.getVelocity().lengthSquared() < FACTOR_ACTIVE_GLIDE_SPEED * FACTOR_ACTIVE_GLIDE_SPEED) {
            return false;
        }

        return factorStack.get(0) < 0.5 && factorStack.get(2) < 0.5;
    }

    private float factorVlFor(double centre) {
        if (centre > 160) return 1.5f;
        if (centre < 60) return 0.75f;
        return 1.0f;
    }

    private void decayFactorBuffer() {
        if (factorBuffer <= 0) {
            factorBuffer = 0;
            factorLastInvalidTime = 0;
            return;
        }

        factorBuffer = Math.max(0, factorBuffer - FACTOR_DECAY);
        if (factorBuffer == 0) factorLastInvalidTime = 0;
    }

private void processSmoothComponent(float deltaYaw, float deltaPitch,
                                        float absDeltaYaw, float absDeltaPitch) {
        double angle = AimStatistics.getAngleInDegrees(deltaYaw, deltaPitch) % 90;

if ((absDeltaPitch > 1.5 && absDeltaYaw > 0.32) || absDeltaYaw > 1.5) {

if (absDeltaPitch < 0.05f && absDeltaYaw > 0.5f) {

                return;
            }
            smoothStack.add(angle);
        }
        if (smoothStack.size() >= 20) {
            List<Float> jiff = AimStatistics.getJiffDelta(smoothStack, 1);
            float prev = 999;
            float prePrev = 999;
            float prePrePrev = 999;

for (float f : jiff) {
                if (f == 0.0 && prev == 0.0 && prePrev == 0.0 && prePrePrev == 0.0) {
                    flagAndAlert(3.5, aetPlayer.player, null, f);
                    break;
                }
                prePrePrev = prePrev;
                prePrev = prev;
                prev = f;
            }
            smoothStack.clear();
        }
    }
}
