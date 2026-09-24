package com.antielytratarget.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AimStatisticsRegressionSimulation {

    private AimStatisticsRegressionSimulation() {
    }

    public static void main(String[] args) {
        vanillaMouseQuantizationMustNotBeEvidence();
        oneSidedOutliersMustNotBeEvidence();
        sustainedDetectorInputMustRemainAvailable();
        System.out.println("AimStatistics regression simulation passed.");
    }

    private static void vanillaMouseQuantizationMustNotBeEvidence() {
        List<Float> legalYawDeltas = Arrays.asList(
                4.9500003f, 4.7999997f, 5.1000004f, 5.550001f, 5.25f,
                4.950001f, 4.5f, 4.200001f, 4.049999f, 3.5999985f,
                3.75f, 3.75f, 3.2999992f, 3.0f, 3.0f,
                2.8499985f, 2.4000015f, 2.4000015f, 2.25f, 2.699997f,
                2.550003f, 2.4000015f, 2.550003f, 2.25f, 2.25f
        );

        int legacyPatternValue = legacyScientificDuplicatePairCount(
                AimStatistics.getJiffDelta(legalYawDeltas, 5));
        require(legacyPatternValue == 20,
                "The captured vanilla lattice regression must reproduce legacy value=20");

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(
                        legalYawDeltas, 2.0, 10.0, 55.0);
        require(!evidence.suspicious(),
                "Vanilla quantized mouse movement must not be suspicious evidence");
    }

    private static void oneSidedOutliersMustNotBeEvidence() {
        List<Float> samples = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            samples.add(1.0f + (i % 3) * 0.05f);
        }
        samples.add(20.0f);
        samples.add(22.0f);

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(samples, 2.0, 10.0, 55.0);
        require(!evidence.suspicious(),
                "One-direction manual flicks must not satisfy bidirectional evidence");
    }

    private static void sustainedDetectorInputMustRemainAvailable() {
        List<Float> samples = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            samples.add((i & 1) == 0 ? 1.0f : -1.0f);
        }
        samples.add(20.0f);
        samples.add(-20.0f);

        AimStatistics.BidirectionalOutlierEvidence evidence =
                AimStatistics.getBidirectionalOutlierEvidence(samples, 2.0, 10.0, 55.0);
        require(evidence.suspicious(),
                "Strong opposite-direction statistical outliers should remain detectable");
        require(Math.abs(evidence.maxAbsoluteOutlier() - 20.0) < 1.0e-9,
                "Unexpected maximum outlier value");
    }

    private static int legacyScientificDuplicatePairCount(List<Float> values) {
        int pairs = 0;
        for (int i = 0; i < values.size(); i++) {
            float value = values.get(i);
            if (value == 0.0f || Math.abs(value) < 1.0e-6f) continue;
            if (!String.valueOf(value).contains("E")) continue;

            for (int other = 0; other < values.size(); other++) {
                if (other != i && value == values.get(other)) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
