package com.antielytratarget.replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;

public final class ReplayPhysicsRegressionSimulation {

    private ReplayPhysicsRegressionSimulation() {
    }

    public static void main(String[] args) throws Exception {
        verifyFireworkMetadataCompatibility();
        verifyFireworkChangesSimulatedFlight();
        verifyCompressedReplayRoundTrip();
        System.out.println("Replay physics regression simulation passed.");
    }

    private static void verifyFireworkMetadataCompatibility() {
        ReplaySnapshot original = snapshot(0.0, 100.0, 0.0, 0.0, 0.0, 0.3, 2, true);
        ReplaySnapshot decoded = ReplaySnapshot.fromLine(original.toLine());
        require(decoded != null && decoded.getFireworkPower() == 2 && decoded.isFireworkStart(),
                "new replay frames must preserve firework power and launch edge");

        String legacyLine = String.join(";",
                "0", "0", "100", "0", "0", "0", "world",
                "0", "0", "1", "0", "0",
                "AIR", "AIR", "AIR", "ELYTRA", "AIR", "AIR");
        ReplaySnapshot legacy = ReplaySnapshot.fromLine(legacyLine);
        require(legacy != null && legacy.getFireworkPower() == 0 && !legacy.isFireworkStart(),
                "legacy replay frames must remain readable");
    }

    private static void verifyFireworkChangesSimulatedFlight() {
        ReplayFlightSimulator withoutBoost = new ReplayFlightSimulator();
        ReplayFlightSimulator withBoost = new ReplayFlightSimulator();
        ReplaySnapshot first = snapshot(0.0, 100.0, 0.0, 0.0, 0.0, 0.25, 0, false);
        withoutBoost.advance(first, true);
        withBoost.advance(first, true);

        ReplaySnapshot normalFrame = snapshot(0.0, 99.95, 0.25, 0.0, -0.02, 0.25, 0, false);
        ReplaySnapshot boostFrame = snapshot(0.0, 99.95, 0.25, 0.0, -0.02, 0.25, 1, true);
        ReplayFlightSimulator.Frame normal = withoutBoost.advance(normalFrame, false);
        ReplayFlightSimulator.Frame boosted = withBoost.advance(boostFrame, false);

        require(!normal.hardCorrection() && !boosted.hardCorrection(),
                "ordinary sequential flight frames should use relative simulated movement");
        require(boosted.z() > normal.z() + 0.2,
                "an active firework must physically accelerate the replay fake player");
    }

    private static void verifyCompressedReplayRoundTrip() throws Exception {
        Path directory = Files.createTempDirectory("aet-replay-regression-");
        Path file = directory.resolve("roundtrip.replay");
        try {
            ReplayData data = new ReplayData(UUID.randomUUID(), "ReplayTest", "regression", 1);
            data.addSnapshot(snapshot(1.0, 80.0, 2.0, 0.1, -0.1, 0.3, 1, true));
            data.saveToFile(file.toFile());

            byte[] prefix = Files.readAllBytes(file);
            require(prefix.length >= 2 && (prefix[0] & 0xff) == 0x1f && (prefix[1] & 0xff) == 0x8b,
                    "new replay files must use GZIP compression");

            ReplayData loaded = ReplayData.loadFromFile(file.toFile());
            ReplayData.Summary summary = ReplayData.loadSummaryFromFile(file.toFile());
            require(loaded.snapshotCount() == 1 && loaded.getSnapshots().get(0).getFireworkPower() == 1,
                    "compressed replay payload must round-trip");
            require(summary.getSnapshotCount() == 1,
                    "compressed replay summaries must use the header frame count");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static ReplaySnapshot snapshot(double x, double y, double z,
                                           double velocityX, double velocityY, double velocityZ,
                                           int fireworkPower, boolean fireworkStart) {
        return new ReplaySnapshot(
                x, y, z, 0.0f, 0.0f, "world",
                false, false, true, false, false,
                "AIR", "FIREWORK_ROCKET", "AIR", "ELYTRA", "AIR", "AIR",
                0L,
                velocityX, velocityY, velocityZ,
                false, true, 1L, 1L,
                Collections.emptyList(), ReplaySkinSnapshot.empty(), Collections.emptyList(),
                0.4, fireworkPower, fireworkStart);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
