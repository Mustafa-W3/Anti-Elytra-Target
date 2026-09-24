package com.antielytratarget.check.misc;

public final class RotationMismatchEvidenceRegressionSimulation {

    private RotationMismatchEvidenceRegressionSimulation() {
    }

    public static void main(String[] args) {
        verifyIsolatedMismatchesDoNotFlag();
        verifySameTickBatchCountsOnce();
        verifyValidRotationClearsEvidence();
        verifyRepeatedDistinctTicksFlagOnce();
        System.out.println("Rotation mismatch evidence regressions passed.");
    }

    private static void verifyIsolatedMismatchesDoNotFlag() {
        RotationMismatchEvidence evidence = new RotationMismatchEvidence();
        require(!evidence.record(10L, 8.6140).ready());
        require(!evidence.record(231L, 2.3306).ready());
        require(!evidence.record(2_011L, 3.4453).ready());
    }

    private static void verifySameTickBatchCountsOnce() {
        RotationMismatchEvidence evidence = new RotationMismatchEvidence();
        require(evidence.record(42L, 2.0).count() == 1);
        require(evidence.record(42L, 4.0).count() == 1);
        require(evidence.record(42L, 8.0).count() == 1);
        require(!evidence.record(42L, 8.0).ready());
    }

    private static void verifyValidRotationClearsEvidence() {
        RotationMismatchEvidence evidence = new RotationMismatchEvidence();
        require(!evidence.record(100L, 3.0).ready());
        require(!evidence.record(105L, 4.0).ready());
        evidence.recordValid(105L);
        require(evidence.record(100L, 9.0).count() == 0);
        require(evidence.record(110L, 5.0).count() == 1);
    }

    private static void verifyRepeatedDistinctTicksFlagOnce() {
        RotationMismatchEvidence evidence = new RotationMismatchEvidence();
        require(!evidence.record(500L, 2.0).ready());
        require(!evidence.record(505L, 8.6140).ready());
        RotationMismatchEvidence.Observation ready =
                evidence.record(510L, 3.0);
        require(ready.ready());
        require(ready.count() == RotationMismatchEvidence.REQUIRED_MISMATCHES);
        require(Math.abs(ready.maxDelta() - 8.6140) < 0.000001);
        require(evidence.record(515L, 1.0).count() == 1);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
    }
}
