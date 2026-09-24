package com.antielytratarget.check.elytra;

import com.antielytratarget.netty.PlayerPacketData;

import java.util.Locale;
import java.util.Random;

public final class RotationPredictionSimulation {

    private static final long SEED = 20_260_711L;
    private static final int RANDOM_CONTINUOUS_STEPS = 1_000;
    private static final int LEGAL_SNAPS_PER_STEP = 80;
    private static final int RAW_SNAPS_PER_VANILLA_STEP = 1_000;
    private static final int[] PRIMITIVE_COUNTS = {1, 2, 3, 5, 7, 4, 9, 11};

    private RotationPredictionSimulation() {
    }

    public static void main(String[] args) {
        deterministicGeometryRegressions();
        livingEntityGeometryRegressions();
        orbitLockRegressions();
        packetSourceRegressions();
        deterministicLatticeRegressions();

        StressResult stress = runProductionModelStress();
        System.out.println("RotationPredictionSimulation{"+
                "seed=" + SEED +
                ", legalSnaps=" + stress.legalSnaps() +
                ", legalMismatches=" + stress.legalMismatches() +
                ", rawSnaps=" + stress.rawSnaps() +
                ", rawMismatches=" + stress.rawMismatches() +
                ", rawMismatchRate=" + String.format(Locale.ROOT, "%.6f",
                stress.rawMismatches() / (double) stress.rawSnaps()) +
                '}');

        require(stress.legalMismatches() == 0,
                "a supported legal mouse-lattice snap became mismatch evidence");
        require(stress.rawMismatches() / (double) stress.rawSnaps() >= 0.25,
                "raw-snap mismatch coverage fell below the conservative 25% floor");
    }

    private static void deterministicGeometryRegressions() {
        double wrapped = Math.abs(RotationPredictionModel.wrappedYawDelta(179.0f, -179.0f));
        require(close(wrapped, 2.0, 1.0E-9), "179 -> -179 must be a 2 degree turn");
        require(RotationPredictionModel.hasRepresentationJump(179.0f, -179.0f, 1.0),
                "yaw representation wrap must fail open");
        require(close(RotationPredictionModel.qualifyingRapidYaw(
                        0.0f, 180.0f, 75.0), 180.0, 1.0E-9),
                "180-degree hit flick must qualify");
        require(close(RotationPredictionModel.qualifyingRapidYaw(
                        10.0f, -160.0f, 75.0), 170.0, 1.0E-9),
                "170-degree hit flick must qualify");
        require(close(RotationPredictionModel.qualifyingRapidYaw(
                        -40.0f, 50.0f, 75.0), 90.0, 1.0E-9),
                "90-degree hit flick must qualify");
        require(RotationPredictionModel.qualifyingRapidYaw(
                        179.0f, -179.0f, 75.0) == 0.0,
                "two-degree yaw representation wrap must not qualify");
        require(RotationPredictionModel.qualifyingRapidYaw(
                        0.0f, 74.99f, 75.0) == 0.0,
                "sub-threshold hit flick must not qualify");

        double diveRotation = RotationPredictionModel.geodesicRotation(
                0.0f, 80.0f, 60.0f, 80.0f);
        require(close(diveRotation, 9.96185, 0.001),
                "geodesic rotation regression at pitch=80/yawDelta=60");

        double closePassTurn = Math.toDegrees(Math.atan2(2.0, 3.0));
        double oldThreshold = 45.0 * Math.max(0.3, 1.0 - (2.0 - 0.5) * 0.2);
        require(closePassTurn > oldThreshold,
                "close-pass baseline must exceed the legacy speed-scaled threshold");
        require(!RotationPredictionModel.isGeometryCandidate(
                        0.0, 0.0, closePassTurn,
                        18.0, 0.20, 12.0, 160.0, 0.985, 0.35),
                "ordinary tracking without acquisition error must not be a snap candidate");
        require(RotationPredictionModel.isGeometryCandidate(
                        47.03, 0.0, 47.03,
                        18.0, 0.20, 12.0, 160.0, 0.985, 0.35),
                "exact high-angle acquisition must remain a geometry candidate");
    }

    private static void packetSourceRegressions() {
        PlayerPacketData data = new PlayerPacketData();
        data.updateRotation(10.0f, 20.0f);
        data.updateRotation(35.0f, 25.0f);

        PlayerPacketData.RotationSample exact = data.findRotationSampleAfter(
                0L, 10.0f, 20.0f, 35.0f, 25.0f);
        require(exact != null, "exact packet/event rotation must match");
        require(data.findRotationSampleAfter(
                        0L, 10.0f, 20.0f, 35.0001f, 25.0f) == null,
                "a plugin-mutated Bukkit event rotation must fail open");
    }

    private static void livingEntityGeometryRegressions() {
        assertEntityBoxMatches("PLAYER", 3.0, 0.0, 3.0, 0.6, 1.8, 1.62);
        assertEntityBoxMatches("ZOMBIE", 3.0, 0.0, 3.0, 0.6, 1.95, 1.74);
        assertEntityBoxMatches("COW", 2.5, 0.0, 2.5, 0.9, 1.4, 1.3);
        assertEntityBoxMatches("VILLAGER", 2.8, 0.0, 2.8, 0.6, 1.95, 1.62);
        assertEntityBoxMatches("ARMOR_STAND", 2.2, 0.0, 2.2, 0.5, 1.975, 1.7775);
        assertEntityBoxMatches("RABBIT", 0.9, 0.0, 0.9, 0.4, 0.5, 0.4);
        assertEntityBoxMatches("ENDERMAN", 3.4, 0.0, 3.4, 0.6, 2.9, 2.55);

double eyeX = 0.0, eyeY = 1.62, eyeZ = 0.0;
        double[] edgeRotation = rotationTo(eyeX, eyeY, eyeZ, 2.72, 0.45, 3.0);
        float toYaw = (float) edgeRotation[0];
        float toPitch = (float) edgeRotation[1];
        float fromYaw = toYaw + 45.0f;
        double rotation = RotationPredictionModel.geodesicRotation(
                fromYaw, toPitch, toYaw, toPitch);
        RotationPredictionTargetModel.Match edge = RotationPredictionTargetModel.match(
                new RotationPredictionTargetModel.Frame(
                        eyeX, eyeY, eyeZ, fromYaw, toPitch, toYaw, toPitch, rotation),
                new RotationPredictionTargetModel.TargetBox(
                        2.7, 0.0, 2.7, 3.3, 1.8, 3.3,
                        3.0, 1.62, 3.0),
                0.75, 10.0, 18.0, 0.20, 12.0, 160.0, 0.985, 0.35);
        require(edge != null && edge.rayIntersectsBox(),
                "non-central hitbox ray must associate a LivingEntity hit");
    }

    private static void orbitLockRegressions() {
        long firstNanos = 1_000_000_000L;
        long stepNanos = 50_000_000L;

        java.util.List<RotationPredictionOrbitModel.Frame> stationary =
                orbitFrames(firstNanos, stepNanos, 44, 3.0, 7.5,
                        0.0, 0.0, false, 0.0);
        long stationaryHit = firstNanos + 43L * stepNanos;
        RotationPredictionOrbitModel.Result stationaryResult =
                RotationPredictionOrbitModel.analyze(
                        stationary,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        stationaryHit, 0.75, 10.0);
        require(stationaryResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "stationary zombie exact orbit must produce ORBIT_LOCK");
        require(stationaryResult.netOrbit() >= 220.0,
                "stationary orbit fixture must exceed the safe fly-by boundary");

        java.util.List<RotationPredictionOrbitModel.Frame> reverse =
                orbitFrames(firstNanos, stepNanos, 44, 3.0, -7.5,
                        0.0, 0.0, false, 0.0);
        RotationPredictionOrbitModel.Result reverseResult =
                RotationPredictionOrbitModel.analyze(
                        reverse,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 43L * stepNanos, 0.75, 10.0);
        require(reverseResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "reverse orbit and negative yaw wrap must produce ORBIT_LOCK");

        java.util.List<RotationPredictionOrbitModel.Frame> closeOrbit =
                orbitFrames(firstNanos, stepNanos, 48, 0.9, 8.0,
                        0.0, 0.0, true, 0.0);
        RotationPredictionOrbitModel.Result closeResult =
                RotationPredictionOrbitModel.analyze(
                        closeOrbit,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(closeResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "sub-1.25-block zombie orbit must remain detectable");

        java.util.List<RotationPredictionOrbitModel.Frame> closePassTargetLock =
                variableRadiusTargetLockFrames(firstNanos, stepNanos, 52);
        RotationPredictionOrbitModel.Result closePassLockResult =
                RotationPredictionOrbitModel.analyze(
                        closePassTargetLock,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 51L * stepNanos, 0.35, 8.5);
        require(closePassLockResult.mode()
                        == RotationPredictionOrbitModel.Mode.TARGET_ROTATION_LOCK,
                "variable-radius close passes must produce TARGET_ROTATION_LOCK");
        require(closePassLockResult.radiusCv() > 0.22,
                "close-pass fixture must exercise the old fixed-radius false negative");

        for (double anchorFraction : new double[]{0.13, 0.63, 0.88}) {
            java.util.List<RotationPredictionOrbitModel.Frame> torsoLock =
                    orbitFrames(firstNanos, stepNanos, 48, 3.0, 7.5,
                            0.0, 0.0, true, 0.0, anchorFraction);
            RotationPredictionOrbitModel.Result torsoResult =
                    RotationPredictionOrbitModel.analyze(
                            torsoLock,
                            new RotationPredictionOrbitModel.Target(
                                    0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                    0.0, 0.0, 0.0),
                            firstNanos + 47L * stepNanos, 0.75, 10.0);
            require(torsoResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                    "fixed torso anchor " + anchorFraction + " must produce ORBIT_LOCK");
        }

        java.util.List<RotationPredictionOrbitModel.Frame> quantized =
                orbitFrames(firstNanos, stepNanos, 48, 3.2, 7.0,
                        0.0, 0.0, true, 0.0);
        RotationPredictionOrbitModel.Result quantizedResult =
                RotationPredictionOrbitModel.analyze(
                        quantized,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(quantizedResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "GCD-quantized orbit must not depend on lattice mismatch");

        for (double sensitivity : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
            java.util.List<RotationPredictionOrbitModel.Frame> floorQuantized =
                    floorQuantizedOrbitFrames(
                            firstNanos, stepNanos, 52, 3.0, 7.5, sensitivity);
            RotationPredictionOrbitModel.Result floorResult =
                    RotationPredictionOrbitModel.analyze(
                            floorQuantized,
                            new RotationPredictionOrbitModel.Target(
                                    0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                    0.0, 0.0, 0.0),
                            firstNanos + 51L * stepNanos, 0.75, 10.0);
            require(floorResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                    "floor-GCD orbit must survive sensitivity " + sensitivity);
        }

        double zombieVelocityX = 0.03;
        java.util.List<RotationPredictionOrbitModel.Frame> moving =
                orbitFrames(firstNanos, stepNanos, 46, 3.0, 7.5,
                        zombieVelocityX, 0.0, true, 0.0);
        long movingHit = firstNanos + 45L * stepNanos;
        double finalZombieX = zombieVelocityX * 45.0;
        RotationPredictionOrbitModel.Result movingResult =
                RotationPredictionOrbitModel.analyze(
                        moving,
                        new RotationPredictionOrbitModel.Target(
                                finalZombieX, 0.9, 0.0,
                                finalZombieX, 1.74, 0.0,
                                zombieVelocityX, 0.0, 0.0),
                        movingHit, 0.75, 10.0);
        require(movingResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "constant-velocity zombie rewind must preserve ORBIT_LOCK");

        java.util.List<RotationPredictionOrbitModel.Frame> progressive =
                orbitFrames(firstNanos, stepNanos, 52, 3.0, 7.5,
                        0.0, 0.0, true, 0.0);
        java.util.List<RotationPredictionOrbitModel.TargetPose> progressivePoses =
                new java.util.ArrayList<>();
        long previousEvidenceFrame = 0L;
        for (int hitIndex : new int[]{32, 40, 48}) {
            long hitNanos = firstNanos + hitIndex * stepNanos;
            progressivePoses.add(new RotationPredictionOrbitModel.TargetPose(
                    hitNanos, 0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                    0.0, 0.0, 0.0));
            RotationPredictionOrbitModel.Result progressiveResult =
                    RotationPredictionOrbitModel.analyze(
                            progressive,
                            new RotationPredictionOrbitModel.Target(
                                    0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                    0.0, 0.0, 0.0,
                                    new java.util.ArrayList<>(progressivePoses)),
                            hitNanos, 0.75, 10.0);
            require(progressiveResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                    "adding hit-time target poses must not re-warm the orbit window");
            if (previousEvidenceFrame != 0L) {
                require(progressiveResult.lastFrameNanos() - previousEvidenceFrame
                                >= 8L * stepNanos,
                        "progressive orbit confirmations must contain eight new frames");
            }
            previousEvidenceFrame = progressiveResult.lastFrameNanos();
        }

        int[] poseIndices = {8, 16, 24, 32, 40, 48, 55};
        double[] poseX = {0.24, 0.48, 0.48, 0.24, 0.0, -0.24, -0.03};
        double[] poseZ = {0.0, 0.0, 0.24, 0.48, 0.48, 0.24, 0.03};
        java.util.List<RotationPredictionOrbitModel.TargetPose> knockbackPoses =
                new java.util.ArrayList<>();
        for (int pose = 0; pose < poseIndices.length; pose++) {
            knockbackPoses.add(new RotationPredictionOrbitModel.TargetPose(
                    firstNanos + poseIndices[pose] * stepNanos,
                    poseX[pose], 0.9, poseZ[pose],
                    poseX[pose], 1.74, poseZ[pose],
                    pose == 0 ? 0.03 : 0.0, 0.0, 0.0));
        }
        java.util.List<RotationPredictionOrbitModel.Frame> knockbackFrames =
                new java.util.ArrayList<>();
        for (int frame = 0; frame <= 55; frame++) {
            double targetX;
            double targetZ;
            if (frame < poseIndices[0]) {
                targetX = 0.03 * frame;
                targetZ = 0.0;
            } else {
                int segment = 0;
                while (segment + 1 < poseIndices.length
                        && frame > poseIndices[segment + 1]) {
                    segment++;
                }
                if (segment + 1 == poseIndices.length) {
                    targetX = poseX[segment];
                    targetZ = poseZ[segment];
                } else {
                    double alpha = (frame - poseIndices[segment])
                            / (double) (poseIndices[segment + 1] - poseIndices[segment]);
                    targetX = poseX[segment]
                            + (poseX[segment + 1] - poseX[segment]) * alpha;
                    targetZ = poseZ[segment]
                            + (poseZ[segment + 1] - poseZ[segment]) * alpha;
                }
            }
            double theta = Math.toRadians(frame * 7.5);
            double playerX = targetX + 3.0 * Math.cos(theta);
            double playerZ = targetZ + 3.0 * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, targetX, 1.74, targetZ);
            knockbackFrames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
        }
        RotationPredictionOrbitModel.Result knockbackResult =
                RotationPredictionOrbitModel.analyze(
                        knockbackFrames,
                        new RotationPredictionOrbitModel.Target(
                                poseX[poseX.length - 1], 0.9, poseZ[poseZ.length - 1],
                                poseX[poseX.length - 1], 1.74, poseZ[poseZ.length - 1],
                                0.0, 0.0, 0.0, knockbackPoses),
                        firstNanos + 55L * stepNanos, 0.75, 10.0);
        require(knockbackResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "piecewise zombie knockback trajectory must preserve ORBIT_LOCK");

        long halfStepNanos = 25_000_000L;
        java.util.List<RotationPredictionOrbitModel.TargetPose> denseTargetPoses =
                new java.util.ArrayList<>();
        for (int pose = 0; pose <= 94; pose++) {
            double targetX = 0.4 * ((pose + 1) / 2);
            denseTargetPoses.add(new RotationPredictionOrbitModel.TargetPose(
                    firstNanos + pose * halfStepNanos,
                    targetX, 0.9, 0.0, targetX, 1.74, 0.0,
                    0.4, 0.0, 0.0));
        }
        java.util.List<RotationPredictionOrbitModel.Frame> denseTargetFrames =
                new java.util.ArrayList<>();
        for (int frame = 0; frame < 48; frame++) {
            double targetX = 0.4 * frame;
            double theta = Math.toRadians(frame * 7.5);
            double playerX = targetX + 3.0 * Math.cos(theta);
            double playerZ = 3.0 * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, targetX, 1.74, 0.0);
            denseTargetFrames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
        }
        RotationPredictionOrbitModel.Result denseTargetResult =
                RotationPredictionOrbitModel.analyze(
                        denseTargetFrames,
                        new RotationPredictionOrbitModel.Target(
                                18.8, 0.9, 0.0, 18.8, 1.74, 0.0,
                                0.4, 0.0, 0.0, denseTargetPoses),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(denseTargetResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "25ms target samples must not double normal per-tick movement speed");

        java.util.List<RotationPredictionOrbitModel.TargetPose> impulsePoses =
                new java.util.ArrayList<>();
        java.util.List<RotationPredictionOrbitModel.Frame> impulseFrames =
                new java.util.ArrayList<>();
        for (int frame = 0; frame < 48; frame++) {
            double targetX = frame < 20 ? 0.0 : 0.8;
            impulsePoses.add(new RotationPredictionOrbitModel.TargetPose(
                    firstNanos + frame * stepNanos,
                    targetX, 0.9, 0.0, targetX, 1.74, 0.0,
                    frame == 20 ? 0.8 : 0.0, 0.0, 0.0));
            double theta = Math.toRadians(frame * 7.5);
            double playerX = targetX + 3.0 * Math.cos(theta);
            double playerZ = 3.0 * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, targetX, 1.74, 0.0);
            impulseFrames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
        }
        RotationPredictionOrbitModel.Result impulseResult =
                RotationPredictionOrbitModel.analyze(
                        impulseFrames,
                        new RotationPredictionOrbitModel.Target(
                                0.8, 0.9, 0.0, 0.8, 1.74, 0.0,
                                0.0, 0.0, 0.0, impulsePoses),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(impulseResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "a measured 0.8-block zombie knockback impulse must remain detectable");

        java.util.List<RotationPredictionOrbitModel.TargetPose> teleportTrajectory =
                java.util.List.of(
                        new RotationPredictionOrbitModel.TargetPose(
                                firstNanos, 0.0, 0.9, 0.0, 0.0, 1.74, 0.0),
                        new RotationPredictionOrbitModel.TargetPose(
                                firstNanos + stepNanos,
                                10.0, 0.9, 0.0, 10.0, 1.74, 0.0));
        RotationPredictionOrbitModel.Result teleportResult =
                RotationPredictionOrbitModel.analyze(
                        stationary,
                        new RotationPredictionOrbitModel.Target(
                                10.0, 0.9, 0.0, 10.0, 1.74, 0.0,
                                0.0, 0.0, 0.0, teleportTrajectory),
                        stationaryHit, 0.75, 10.0);
        require(!teleportResult.candidate(),
                "teleported target trajectory must fail open");

        java.util.List<RotationPredictionOrbitModel.TargetPose> duplicateTimeTrajectory =
                java.util.List.of(
                        new RotationPredictionOrbitModel.TargetPose(
                                firstNanos, 0.0, 0.9, 0.0, 0.0, 1.74, 0.0),
                        new RotationPredictionOrbitModel.TargetPose(
                                firstNanos, 0.1, 0.9, 0.0, 0.1, 1.74, 0.0));
        RotationPredictionOrbitModel.Result duplicateTimeResult =
                RotationPredictionOrbitModel.analyze(
                        stationary,
                        new RotationPredictionOrbitModel.Target(
                                0.1, 0.9, 0.0, 0.1, 1.74, 0.0,
                                0.0, 0.0, 0.0, duplicateTimeTrajectory),
                        stationaryHit, 0.75, 10.0);
        require(!duplicateTimeResult.candidate(),
                "duplicate-time target poses must fail open");

        java.util.List<RotationPredictionOrbitModel.Frame> filteredMiddle =
                new java.util.ArrayList<>();
        for (int frame = 0; frame < 50; frame++) {
            double radius = frame >= 20 && frame < 30 ? 0.5 : 3.0;
            double theta = Math.toRadians(frame * 8.0);
            double playerX = radius * Math.cos(theta);
            double playerZ = radius * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, 0.0, 1.74, 0.0);
            filteredMiddle.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
        }
        RotationPredictionOrbitModel.Result filteredMiddleResult =
                RotationPredictionOrbitModel.analyze(
                        filteredMiddle,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 49L * stepNanos, 0.75, 10.0);
        require(!filteredMiddleResult.candidate(),
                "two short orbit fragments must not be joined across a filtered gap");

        java.util.ArrayDeque<RotationPredictionOrbitModel.Frame> retained =
                new java.util.ArrayDeque<>();
        java.util.List<RotationPredictionOrbitModel.Frame> longOrbit =
                orbitFrames(firstNanos, stepNanos, 110, 3.0, 7.5,
                        0.0, 0.0, true, 0.0);
        for (RotationPredictionOrbitModel.Frame frame : longOrbit) {
            retained.addLast(frame);
            while (frame.createdNanos() - retained.peekFirst().createdNanos()
                    > 4_000_000_000L) {
                retained.removeFirst();
            }
        }
        RotationPredictionOrbitModel.Result retainedResult =
                RotationPredictionOrbitModel.analyze(
                        new java.util.ArrayList<>(retained),
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 109L * stepNanos, 0.75, 10.0);
        require(retainedResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "four-second production retention must keep a reachable orbit window");

        java.util.ArrayDeque<RotationPredictionOrbitModel.Frame> coalesced40Hz =
                new java.util.ArrayDeque<>();
        long lastRetainedNanos = 0L;
        for (int packet = 0; packet <= 180; packet++) {
            long createdNanos = firstNanos + packet * halfStepNanos;
            if (lastRetainedNanos != 0L
                    && createdNanos - lastRetainedNanos < 40_000_000L) {
                continue;
            }
            double theta = Math.toRadians(packet * 1.6);
            double playerX = 3.0 * Math.cos(theta);
            double playerZ = 3.0 * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, 0.0, 1.74, 0.0);
            coalesced40Hz.addLast(new RotationPredictionOrbitModel.Frame(
                    createdNanos, playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
            lastRetainedNanos = createdNanos;
            while (coalesced40Hz.size() > 96) coalesced40Hz.removeFirst();
            while (createdNanos - coalesced40Hz.peekFirst().createdNanos()
                    > 4_000_000_000L) {
                coalesced40Hz.removeFirst();
            }
        }
        RotationPredictionOrbitModel.Result slow40HzResult =
                RotationPredictionOrbitModel.analyze(
                        new java.util.ArrayList<>(coalesced40Hz),
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 180L * halfStepNanos, 0.75, 10.0);
        require(slow40HzResult.mode() == RotationPredictionOrbitModel.Mode.ORBIT_LOCK,
                "40Hz coalescing must retain a slow 224-degree/3.5s orbit");

        java.util.List<RotationPredictionOrbitModel.Frame> noisyHuman =
                orbitFrames(firstNanos, stepNanos, 48, 3.0, 7.0,
                        0.0, 0.0, false, 2.0);
        RotationPredictionOrbitModel.Result noisyResult =
                RotationPredictionOrbitModel.analyze(
                        noisyHuman,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(!noisyResult.candidate(),
                "two-degree manual aim noise must fail the fixed-anchor orbit gate");

        java.util.List<RotationPredictionOrbitModel.Frame> flyBy = new java.util.ArrayList<>();
        for (int i = 0; i < 48; i++) {
            double x = -8.0 + i * (16.0 / 47.0);
            double z = 2.5;
            double[] rotation = rotationTo(x, 1.62, z, 0.0, 1.74, 0.0);
            flyBy.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + i * stepNanos, x, 1.62, z,
                    (float) rotation[0], (float) rotation[1]));
        }
        RotationPredictionOrbitModel.Result flyByResult =
                RotationPredictionOrbitModel.analyze(
                        flyBy,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 47L * stepNanos, 0.75, 10.0);
        require(!flyByResult.candidate(),
                "straight target-facing fly-by must remain below the 220-degree orbit gate");

        java.util.List<RotationPredictionOrbitModel.Frame> plainSpin = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            float yaw = (float) (-170.0 + i * (340.0 / 29.0));
            if (i == 29) yaw = 0.0f;
            plainSpin.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + i * stepNanos, 0.0, 1.62, -3.0, yaw, 0.0f));
        }
        RotationPredictionOrbitModel.Result spinResult =
                RotationPredictionOrbitModel.analyze(
                        plainSpin,
                        new RotationPredictionOrbitModel.Target(
                                0.0, 0.9, 0.0, 0.0, 1.74, 0.0,
                                0.0, 0.0, 0.0),
                        firstNanos + 29L * stepNanos, 0.75, 10.0);
        require(!spinResult.candidate(),
                "plain human 360-degree spin plus final hit must not be standalone evidence");
    }

    private static java.util.List<RotationPredictionOrbitModel.Frame> variableRadiusTargetLockFrames(
            long firstNanos,
            long stepNanos,
            int count) {
        java.util.List<RotationPredictionOrbitModel.Frame> frames = new java.util.ArrayList<>();
        for (int frame = 0; frame < count; frame++) {
            double theta = Math.toRadians(frame * 7.5);
            double radius = 2.0 + 1.55 * Math.sin(frame * 0.22);
            double playerX = radius * Math.cos(theta);
            double playerZ = radius * Math.sin(theta);

            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ, 0.0, 1.74, 0.0);
            frames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ,
                    (float) rotation[0], (float) rotation[1]));
        }
        return frames;
    }

    private static java.util.List<RotationPredictionOrbitModel.Frame> orbitFrames(
            long firstNanos,
            long stepNanos,
            int count,
            double radius,
            double degreesPerFrame,
            double targetVelocityX,
            double targetVelocityZ,
            boolean quantized,
            double aimNoiseDegrees) {
        return orbitFrames(firstNanos, stepNanos, count, radius, degreesPerFrame,
                targetVelocityX, targetVelocityZ, quantized, aimNoiseDegrees, 1.0);
    }

    private static java.util.List<RotationPredictionOrbitModel.Frame> orbitFrames(
            long firstNanos,
            long stepNanos,
            int count,
            double radius,
            double degreesPerFrame,
            double targetVelocityX,
            double targetVelocityZ,
            boolean quantized,
            double aimNoiseDegrees,
            double anchorFraction) {
        java.util.List<RotationPredictionOrbitModel.Frame> frames = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            double targetX = targetVelocityX * i;
            double targetZ = targetVelocityZ * i;
            double theta = Math.toRadians(i * degreesPerFrame);
            double playerX = targetX + radius * Math.cos(theta);
            double playerZ = targetZ + radius * Math.sin(theta);
            double[] rotation = rotationTo(
                    playerX, 1.62, playerZ,
                    targetX, 0.9 + (1.74 - 0.9) * anchorFraction, targetZ);
            double yaw = rotation[0];
            double pitch = rotation[1];
            if (quantized) {
                double step = mouseStep(0.5);
                yaw = Math.rint(yaw / step) * step;
                pitch = Math.rint(pitch / step) * step;
            }
            if (aimNoiseDegrees != 0.0) {
                yaw += (i & 1) == 0 ? aimNoiseDegrees : -aimNoiseDegrees;
            }
            frames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + i * stepNanos,
                    playerX, 1.62, playerZ, (float) yaw, (float) pitch));
        }
        return frames;
    }

    private static java.util.List<RotationPredictionOrbitModel.Frame> floorQuantizedOrbitFrames(
            long firstNanos,
            long stepNanos,
            int count,
            double radius,
            double degreesPerFrame,
            double sensitivity) {
        java.util.List<RotationPredictionOrbitModel.Frame> frames = new java.util.ArrayList<>();
        double quantum = mouseStep(sensitivity);
        double yaw = Double.NaN;
        double pitch = Double.NaN;
        for (int frame = 0; frame < count; frame++) {
            double theta = Math.toRadians(frame * degreesPerFrame);
            double playerX = radius * Math.cos(theta);
            double playerZ = radius * Math.sin(theta);
            double[] desired = rotationTo(
                    playerX, 1.62, playerZ, 0.0, 1.74, 0.0);
            if (!Double.isFinite(yaw)) {
                yaw = Math.floor(desired[0] / quantum) * quantum;
                pitch = Math.floor(desired[1] / quantum) * quantum;
            } else {
                double yawDelta = RotationPredictionModel.wrappedYawDelta(
                        (float) yaw, (float) desired[0]);
                double pitchDelta = desired[1] - pitch;
                yaw += Math.copySign(
                        Math.floor(Math.abs(yawDelta) / quantum) * quantum, yawDelta);
                pitch += Math.copySign(
                        Math.floor(Math.abs(pitchDelta) / quantum) * quantum, pitchDelta);
            }
            frames.add(new RotationPredictionOrbitModel.Frame(
                    firstNanos + frame * stepNanos,
                    playerX, 1.62, playerZ, (float) yaw, (float) pitch));
        }
        return frames;
    }

    private static void assertEntityBoxMatches(String type,
                                               double x,
                                               double y,
                                               double z,
                                               double width,
                                               double height,
                                               double eyeHeight) {
        double sourceX = 0.0, sourceY = 1.62, sourceZ = 0.0;
        double targetX = x;
        double targetY = y + eyeHeight;
        double targetZ = z;
        double[] to = rotationTo(sourceX, sourceY, sourceZ, targetX, targetY, targetZ);
        float toYaw = (float) to[0];
        float toPitch = (float) to[1];
        float fromYaw = toYaw + 45.0f;
        double rotation = RotationPredictionModel.geodesicRotation(
                fromYaw, toPitch, toYaw, toPitch);

        RotationPredictionTargetModel.Match match = RotationPredictionTargetModel.match(
                new RotationPredictionTargetModel.Frame(
                        sourceX, sourceY, sourceZ,
                        fromYaw, toPitch, toYaw, toPitch, rotation),
                new RotationPredictionTargetModel.TargetBox(
                        x - width * 0.5, y, z - width * 0.5,
                        x + width * 0.5, y + height, z + width * 0.5,
                        targetX, targetY, targetZ),
                0.75, 10.0, 18.0, 0.20, 12.0, 160.0, 0.985, 0.35);
        require(match != null, type + " hitbox/eye anchor did not match");
    }

    private static double[] rotationTo(double sourceX, double sourceY, double sourceZ,
                                       double targetX, double targetY, double targetZ) {
        double dx = targetX - sourceX;
        double dy = targetY - sourceY;
        double dz = targetZ - sourceZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, horizontal));
        return new double[]{yaw, pitch};
    }

    private static void deterministicLatticeRegressions() {
        double vanillaHalf = mouseStep(0.5);
        RotationPredictionModel.LatticeTracker primitive = train(vanillaHalf, PRIMITIVE_COUNTS);
        require(primitive.calibration() != null,
                "primitive vanilla lattice must calibrate");
        require(primitive.findMismatch(0.0f, 0.0f, 131.16562f, 0.0f) != null,
                "a non-lattice raw snap must remain detectable against a 0.15 lattice");
        require(primitive.findMismatch(
                        0.0f, 0.0f, legalDelta(vanillaHalf, 314), 0.0f) == null,
                "integer-count vanilla snap must fail open");

assertLegalFailsOpen("repeated-two-count harmonic",
                vanillaHalf, new int[]{2}, 300);

double index63Step = mouseStep(0.31690142);
        assertLegalFailsOpen("index63-to-index97 harmonic",
                index63Step, new int[]{6, 10, 14, 18, 22}, 169);

double index169Step = mouseStep(0.8450704);
        assertLegalFailsOpen("index169-neighbour quantization",
                index169Step, PRIMITIVE_COUNTS, 29);

double customStep = mouseStep(0.5005551856);
        require(close(customStep, 0.1503, 0.00001),
                "continuous sensitivity fixture must produce a 0.1503 step");
        assertLegalFailsOpen("continuous options sensitivity",
                customStep, new int[]{3, 5, 7, 9, 11}, 169);

RotationPredictionModel.LatticeTracker outlier = new RotationPredictionModel.LatticeTracker();
        float yaw = 0.0f;
        for (int i = 0; i < 59; i++) {
            float next = (float) (yaw + 0.30f);
            outlier.observe(yaw, 0.0f, next, 0.0f);
            yaw = next;
        }
        float poisoned = (float) (yaw + 0.5235f);
        outlier.observe(yaw, 0.0f, poisoned, 0.0f);
        require(outlier.findMismatch(
                        0.0f, 0.0f, legalDelta(vanillaHalf, 300), 0.0f) == null,
                "a single odd outlier must not poison harmonic ambiguity");

        primitive.reset();
        require(primitive.size() == 0 && primitive.calibration() == null,
                "discontinuity reset must remove calibration and evidence epoch");
    }

    private static StressResult runProductionModelStress() {
        Random random = new Random(SEED);
        long legalSnaps = 0L;
        long legalMismatches = 0L;
        long rawSnaps = 0L;
        long rawMismatches = 0L;

for (int index = 0; index <= 200; index++) {
            double step = mouseStep(index / 200.0);
            RotationPredictionModel.LatticeTracker tracker = train(step, PRIMITIVE_COUNTS);
            require(tracker.calibration() != null,
                    "vanilla slider index " + index + " did not calibrate");

            for (int i = 0; i < LEGAL_SNAPS_PER_STEP; i++) {
                int count = legalSnapCount(step, random);
                float base = switch (i & 3) {
                    case 0 -> 0.0f;
                    case 1 -> 180.0f;
                    case 2 -> 720.0f;
                    default -> 8_192.0f;
                };
                float to = advance(base, step, count);
                legalSnaps++;
                if (tracker.findMismatch(base, 0.0f, to, 0.0f) != null) {
                    legalMismatches++;
                }
            }

            for (int i = 0; i < RAW_SNAPS_PER_VANILLA_STEP; i++) {
                float raw = (float) (12.0 + random.nextDouble() * 148.0);
                rawSnaps++;
                if (tracker.findMismatch(0.0f, 0.0f, raw, 0.0f) != null) {
                    rawMismatches++;
                }
            }
        }

for (int i = 0; i < RANDOM_CONTINUOUS_STEPS; i++) {
            double step = mouseStep(random.nextDouble());
            int[] baseCounts = {1, 2, 3, 5, 7, 4};
            int maximumFactor = Math.max(1,
                    Math.min(8, (int) Math.floor(4.8 / (7.0 * step))));
            int commonFactor = 1 + random.nextInt(maximumFactor);
            int[] counts = new int[baseCounts.length];
            for (int j = 0; j < counts.length; j++) {
                counts[j] = baseCounts[j] * commonFactor;
            }
            RotationPredictionModel.LatticeTracker tracker = train(step, counts);
            require(tracker.calibration() != null,
                    "continuous sensitivity did not calibrate at iteration " + i);
            for (int snap = 0; snap < LEGAL_SNAPS_PER_STEP; snap++) {
                int count = legalSnapCount(step, random);
                float from = snap % 2 == 0 ? 0.0f : 720.0f;
                float to = advance(from, step, count);
                legalSnaps++;
                if (tracker.findMismatch(from, 0.0f, to, 0.0f) != null) {
                    legalMismatches++;
                }
            }
        }
        return new StressResult(legalSnaps, legalMismatches, rawSnaps, rawMismatches);
    }

    private static void assertLegalFailsOpen(String label,
                                             double trueStep,
                                             int[] trainingCounts,
                                             int snapCount) {
        RotationPredictionModel.LatticeTracker tracker = train(trueStep, trainingCounts);
        require(tracker.calibration() != null, label + " did not calibrate");
        float snap = legalDelta(trueStep, snapCount);
        require(tracker.findMismatch(0.0f, 0.0f, snap, 0.0f) == null,
                label + " classified a legal integer-count snap as mismatch");
    }

    private static RotationPredictionModel.LatticeTracker train(double step, int[] counts) {
        RotationPredictionModel.LatticeTracker tracker = new RotationPredictionModel.LatticeTracker();
        float yaw = 0.0f;
        for (int i = 0; i < 80; i++) {
            int signedCount = (i & 1) == 0
                    ? counts[i % counts.length]
                    : -counts[i % counts.length];
            float next = advance(yaw, step, signedCount);
            tracker.observe(yaw, 0.0f, next, 0.0f);
            yaw = next;
        }
        return tracker;
    }

    private static int legalSnapCount(double step, Random random) {
        int minimum = Math.max(1, (int) Math.ceil(12.0 / step));
        int maximum = Math.max(minimum, (int) Math.floor(160.0 / step));
        return minimum + random.nextInt(maximum - minimum + 1);
    }

    private static float legalDelta(double step, int count) {
        return advance(0.0f, step, count);
    }

    private static float advance(float yaw, double step, int count) {
        return (float) (yaw + (float) step * count);
    }

    private static double mouseStep(double sensitivity) {
        float factor = (float) ((float) sensitivity * 0.6f + 0.2f);
        return factor * factor * factor * 8.0f * 0.15f;
    }

    private static boolean close(double actual, double expected, double tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record StressResult(long legalSnaps,
                                long legalMismatches,
                                long rawSnaps,
                                long rawMismatches) {
    }
}
