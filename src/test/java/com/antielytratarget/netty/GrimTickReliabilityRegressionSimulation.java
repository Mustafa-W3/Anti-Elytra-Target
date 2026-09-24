package com.antielytratarget.netty;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;

public final class GrimTickReliabilityRegressionSimulation {

    private static final long TICK_NANOS = 50_000_000L;
    private static final double MOVING_DISTANCE_SQUARED = 0.01 * 0.01;

    private GrimTickReliabilityRegressionSimulation() {
    }

    public static void main(String[] args) {
        verifyNativeEndTickIsReliable();
        verifyThreeMovingTicksAreRequired();
        verifyStationaryMovementResetsReliability();
        verifySilentTickGapResetsReliability();
        verifyCameraExemption();
        verifyVehicleSwitchGrace();
        verifyVersionMovementThresholds();
    }

    private static void verifyNativeEndTickIsReliable() {
        GrimTickReliability tracker = new GrimTickReliability();
        tracker.onTickBoundary(
                ClientVersion.V_1_21_2,
                false,
                false,
                false,
                Double.POSITIVE_INFINITY,
                false,
                1,
                TICK_NANOS);

        require(tracker.isTickingReliablyFor(3, false, true),
                "native end-tick clients must not need movement inference");
    }

    private static void verifyThreeMovingTicksAreRequired() {
        GrimTickReliability tracker = new GrimTickReliability();

        movingBoundary(tracker, ClientVersion.V_1_20, 1, TICK_NANOS, false);
        movingBoundary(tracker, ClientVersion.V_1_20, 2, TICK_NANOS * 2, false);
        require(!tracker.isTickingReliablyFor(3, true, true),
                "two moving ticks must not validate queued packet-order flags");

        movingBoundary(tracker, ClientVersion.V_1_20, 3, TICK_NANOS * 3, false);
        require(tracker.isTickingReliablyFor(3, true, true),
                "three consecutive above-threshold ticks should be reliable");
    }

    private static void verifyStationaryMovementResetsReliability() {
        GrimTickReliability tracker = reliableTracker();
        tracker.onTickBoundary(
                ClientVersion.V_1_20,
                true,
                true,
                true,
                0.0,
                false,
                4,
                TICK_NANOS * 4);

        require(!tracker.isTickingReliablyFor(3, true, true),
                "a stationary/point-three movement must invalidate the streak");
        require(tracker.consecutiveReliableMovements() == 0,
                "stationary movement must fully reset the streak");
    }

    private static void verifySilentTickGapResetsReliability() {
        GrimTickReliability tracker = reliableTracker();

        movingBoundary(tracker, ClientVersion.V_1_20, 5, TICK_NANOS * 5, false);

        require(!tracker.isTickingReliablyFor(3, true, true),
                "a silent tick gap must discard queued packet-order candidates");
        require(tracker.consecutiveReliableMovements() == 1,
                "the first movement after a gap starts a new streak");
    }

    private static void verifyCameraExemption() {
        GrimTickReliability tracker = reliableTracker();
        require(!tracker.isTickingReliablyFor(3, true, false),
                "non-self camera state must always be exempt");
    }

    private static void verifyVehicleSwitchGrace() {
        GrimTickReliability tracker = reliableTracker();
        movingBoundary(tracker, ClientVersion.V_1_20, 4, TICK_NANOS * 4, true);

        require(!tracker.isTickingReliablyFor(3, true, true),
                "the vehicle switch tick must not validate queued flags");

        movingBoundary(tracker, ClientVersion.V_1_20, 5, TICK_NANOS * 5, true);
        require(!tracker.isTickingReliablyFor(3, true, true),
                "one full tick after a vehicle switch remains exempt");
    }

    private static void verifyVersionMovementThresholds() {
        require(GrimTickReliability.movementThreshold(ClientVersion.V_1_17_1) == 0.03,
                "pre-1.18.2 clients must use Grim's 0.03 threshold");
        require(GrimTickReliability.movementThreshold(ClientVersion.V_1_20) == 0.0002,
                "modern clients must use Grim's 0.0002 threshold");
    }

    private static GrimTickReliability reliableTracker() {
        GrimTickReliability tracker = new GrimTickReliability();
        movingBoundary(tracker, ClientVersion.V_1_20, 1, TICK_NANOS, false);
        movingBoundary(tracker, ClientVersion.V_1_20, 2, TICK_NANOS * 2, false);
        movingBoundary(tracker, ClientVersion.V_1_20, 3, TICK_NANOS * 3, false);
        require(tracker.isTickingReliablyFor(3, true, true),
                "test setup must create a reliable tracker");
        return tracker;
    }

    private static void movingBoundary(GrimTickReliability tracker,
                                       ClientVersion version,
                                       long serverTick,
                                       long receivedNanos,
                                       boolean inVehicle) {
        tracker.onTickBoundary(
                version,
                true,
                true,
                true,
                MOVING_DISTANCE_SQUARED,
                inVehicle,
                serverTick,
                receivedNanos);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
