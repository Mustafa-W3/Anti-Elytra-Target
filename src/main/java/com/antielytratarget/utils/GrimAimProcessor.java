package com.antielytratarget.utils;

import java.util.*;

public class GrimAimProcessor {

    private static final int TOTAL_SAMPLES     = 80;
    private static final int SIGNIFICANT_COUNT = 15;

public double sensitivityX = 0.0;
    public double sensitivityY = 0.0;
    public double divisorX     = 0.0;
    public double divisorY     = 0.0;
    public double modeX        = 0.0;
    public double modeY        = 0.0;
    public double deltaDotsX   = 0.0;
    public double deltaDotsY   = 0.0;

private final RunningMode xRotMode = new RunningMode(TOTAL_SAMPLES);
    private final RunningMode yRotMode = new RunningMode(TOTAL_SAMPLES);

    private float lastDeltaX = 0f;
    private float lastDeltaY = 0f;

private static final double MINIMUM_DIVISOR = 1e-5;

public void process(float deltaYawAbs, float deltaPitchAbs) {

double gcdX = gcd(deltaYawAbs, lastDeltaX);
        if (deltaYawAbs > 0 && deltaYawAbs < 5 && gcdX > MINIMUM_DIVISOR) {
            divisorX = gcdX;
            xRotMode.add(gcdX);
            lastDeltaX = deltaYawAbs;
        }

double gcdY = gcd(deltaPitchAbs, lastDeltaY);
        if (deltaPitchAbs > 0 && deltaPitchAbs < 5 && gcdY > MINIMUM_DIVISOR) {
            divisorY = gcdY;
            yRotMode.add(gcdY);
            lastDeltaY = deltaPitchAbs;
        }

if (xRotMode.size() > SIGNIFICANT_COUNT) {
            double[] m = xRotMode.getMode();
            if (m[1] > SIGNIFICANT_COUNT) {
                modeX = m[0];
                sensitivityX = convertToSensitivity(modeX);
            }
        }

        if (yRotMode.size() > SIGNIFICANT_COUNT) {
            double[] m = yRotMode.getMode();
            if (m[1] > SIGNIFICANT_COUNT) {
                modeY = m[0];
                sensitivityY = convertToSensitivity(modeY);
            }
        }

if (modeX > MINIMUM_DIVISOR) deltaDotsX = deltaYawAbs   / modeX;
        if (modeY > MINIMUM_DIVISOR) deltaDotsY = deltaPitchAbs / modeY;
    }

public boolean isCalibrated() {
        return sensitivityX > 0.0 && sensitivityY > 0.0;
    }

public static double convertToSensitivity(double divisor) {
        double step1 = divisor / 0.15 / 8.0;
        double step2 = Math.cbrt(step1);
        return (step2 - 0.2) / 0.6;
    }

public static double gcd(double a, double b) {
        if (a < MINIMUM_DIVISOR) return b;
        if (b < MINIMUM_DIVISOR) return a;
        return gcd(b, a % b);
    }

private static class RunningMode {
        private final int capacity;
        private final ArrayDeque<Double> window;
        private final Map<Double, Integer> freq;

        RunningMode(int capacity) {
            this.capacity = capacity;
            this.window   = new ArrayDeque<>(capacity);
            this.freq     = new LinkedHashMap<>();
        }

        void add(double value) {
            double rounded = Math.round(value * 1_000_000.0) / 1_000_000.0;

            if (window.size() >= capacity) {
                double evicted = window.pollFirst();
                int count = freq.getOrDefault(evicted, 1) - 1;
                if (count <= 0) freq.remove(evicted);
                else freq.put(evicted, count);
            }

            window.addLast(rounded);
            freq.merge(rounded, 1, Integer::sum);
        }

        int size() { return window.size(); }

double[] getMode() {
            double bestVal = 0; int bestCount = 0;
            for (Map.Entry<Double, Integer> e : freq.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    bestVal   = e.getKey();
                }
            }
            return new double[]{ bestVal, bestCount };
        }
    }
}