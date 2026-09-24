package com.antielytratarget.check.elytra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RotationPredictionOrbitModel {

    private static final long WINDOW_NANOS = 3_500_000_000L;
    private static final long MIN_ORBIT_DURATION_NANOS = 900_000_000L;
    private static final int MIN_ORBIT_SAMPLES = 28;
    private static final long MAX_SAMPLE_GAP_NANOS = 125_000_000L;
    private static final double MAX_TARGET_HORIZONTAL_SPEED = 0.45;
    private static final double MAX_TARGET_VERTICAL_SPEED = 0.60;
    private static final double MAX_MEASURED_TARGET_HORIZONTAL_SPEED = 1.25;
    private static final double MAX_MEASURED_TARGET_VERTICAL_SPEED = 1.50;
    private static final double MAX_QUANTIZED_P90_ERROR = 0.95;
    private static final int ANCHOR_FIT_ITERATIONS = 14;

    private RotationPredictionOrbitModel() {
    }

    static Result analyze(List<Frame> history,
                          Target target,
                          long hitNanos,
                          double configuredMinimumDistance,
                          double configuredMaximumDistance) {
        if (history == null || target == null || !target.finite()) return Result.none();
        double targetHorizontalSpeed = Math.hypot(target.velocityX(), target.velocityZ());
        boolean hasTrajectory = target.trajectory() != null && target.trajectory().size() >= 2;
        if (!hasTrajectory && (targetHorizontalSpeed > MAX_TARGET_HORIZONTAL_SPEED
                || Math.abs(target.velocityY()) > MAX_TARGET_VERTICAL_SPEED)) {
            return Result.none();
        }

        double minimumDistance = Math.max(0.25, configuredMinimumDistance);
        double maximumDistance = Math.min(8.5, configuredMaximumDistance);
        if (minimumDistance >= maximumDistance) return Result.none();

        List<Sample> samples = new ArrayList<>(history.size());
        for (Frame frame : history) {
            long age = hitNanos - frame.createdNanos();
            if (age < 0L || age > WINDOW_NANOS || !frame.finite()) continue;

            ResolvedPose resolved = target.resolve(frame.createdNanos(), hitNanos);
            if (resolved == null) continue;
            double targetX = resolved.centerX();
            double targetY = resolved.centerY();
            double targetZ = resolved.centerZ();
            double targetEyeX = resolved.eyeX();
            double targetEyeY = resolved.eyeY();
            double targetEyeZ = resolved.eyeZ();

            double dx = targetX - frame.eyeX();
            double dz = targetZ - frame.eyeZ();
            double radius = Math.hypot(dx, dz);
            if (radius < minimumDistance || radius > maximumDistance) continue;

            double orbitAngle = Math.toDegrees(Math.atan2(
                    frame.eyeZ() - targetZ,
                    frame.eyeX() - targetX));
            samples.add(new Sample(frame, radius,
                    targetX - frame.eyeX(), targetY - frame.eyeY(), targetZ - frame.eyeZ(),
                    targetEyeX - frame.eyeX(), targetEyeY - frame.eyeY(),
                    targetEyeZ - frame.eyeZ(),
                    orbitAngle, targetX, targetZ));
        }
        samples = longestContinuousSegment(samples);
        if (samples.size() < MIN_ORBIT_SAMPLES) return Result.none();

double fixedAnchor = fitFixedAnchor(samples);
        double[] fixedAnchorErrors = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            fixedAnchorErrors[i] = anchorError(samples.get(i), fixedAnchor);
        }
        double fixedAnchorP90 = percentile(fixedAnchorErrors, 0.90);

        double radiusSum = 0.0;
        double aimSum = 0.0;
        double maxAim = 0.0;
        int quantizedLocked = 0;
        for (int i = 0; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            double error = fixedAnchorErrors[i];
            radiusSum += sample.radius();
            aimSum += error;
            maxAim = Math.max(maxAim, error);
            if (error <= MAX_QUANTIZED_P90_ERROR) quantizedLocked++;
        }
        double radiusMean = radiusSum / samples.size();
        double aimMean = aimSum / samples.size();
        double radiusVariance = 0.0;
        for (Sample sample : samples) {
            double difference = sample.radius() - radiusMean;
            radiusVariance += difference * difference;
        }
        double radiusCv = radiusMean > 1.0E-9
                ? Math.sqrt(radiusVariance / samples.size()) / radiusMean
                : Double.POSITIVE_INFINITY;

        double totalYaw = 0.0;
        double signedYaw = 0.0;
        double orbitArc = 0.0;
        double signedOrbit = 0.0;
        double path = 0.0;
        double tangentialPath = 0.0;
        double syncResidualSum = 0.0;
        int syncPairs = 0;
        int rapidTurnPairs = 0;
        for (int i = 1; i < samples.size(); i++) {
            Sample previous = samples.get(i - 1);
            Sample current = samples.get(i);
            double yawDelta = RotationPredictionModel.wrappedYawDelta(
                    previous.frame().yaw(), current.frame().yaw());
            double orbitDelta = wrapDegrees(current.orbitAngle() - previous.orbitAngle());
            totalYaw += Math.abs(yawDelta);
            signedYaw += yawDelta;
            orbitArc += Math.abs(orbitDelta);
            signedOrbit += orbitDelta;
            if (Math.abs(yawDelta) >= 5.0) rapidTurnPairs++;

            double moveX = (current.frame().eyeX() - current.targetX())
                    - (previous.frame().eyeX() - previous.targetX());
            double moveZ = (current.frame().eyeZ() - current.targetZ())
                    - (previous.frame().eyeZ() - previous.targetZ());
            double segment = Math.hypot(moveX, moveZ);
            path += segment;
            if (previous.radius() > 1.0E-9) {
                double radialX = (previous.frame().eyeX() - previous.targetX()) / previous.radius();
                double radialZ = (previous.frame().eyeZ() - previous.targetZ()) / previous.radius();
                double tangentX = -radialZ;
                double tangentZ = radialX;
                tangentialPath += Math.abs(moveX * tangentX + moveZ * tangentZ);
            }
            if (Math.abs(orbitDelta) >= 0.5) {
                syncResidualSum += Math.abs(wrapDegrees(yawDelta - orbitDelta));
                syncPairs++;
            }
        }

        double durationNanos = samples.get(samples.size() - 1).frame().createdNanos()
                - samples.get(0).frame().createdNanos();
        double p90Aim = fixedAnchorP90;
        double lockRatio = quantizedLocked / (double) samples.size();
        double yawDirectionRatio = totalYaw > 1.0E-9
                ? Math.abs(signedYaw) / totalYaw : 0.0;
        double orbitDirectionRatio = orbitArc > 1.0E-9 ? Math.abs(signedOrbit) / orbitArc : 0.0;
        double tangentShare = path > 1.0E-9 ? Math.min(1.0, tangentialPath / path) : 0.0;
        double syncMean = syncPairs > 0 ? syncResidualSum / syncPairs : Double.POSITIVE_INFINITY;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        double yawRate = durationSeconds > 1.0E-9 ? totalYaw / durationSeconds : 0.0;
        double rapidTurnShare = rapidTurnPairs / (double) (samples.size() - 1);
        double minimumOrbitPath = Math.max(2.5,
                radiusMean * Math.toRadians(220.0) * 0.70);

        boolean orbitLock = samples.size() >= MIN_ORBIT_SAMPLES
                && durationNanos >= MIN_ORBIT_DURATION_NANOS
                && Math.abs(signedOrbit) >= 220.0
                && orbitDirectionRatio >= 0.92
                && tangentShare >= 0.78
                && radiusCv <= 0.22
                && path >= minimumOrbitPath
                && p90Aim <= MAX_QUANTIZED_P90_ERROR
                && lockRatio >= 0.90
                && aimMean <= 0.70
                && syncPairs >= 20
                && syncMean <= 1.10;

boolean targetRotationLock = samples.size() >= MIN_ORBIT_SAMPLES
                && durationNanos >= MIN_ORBIT_DURATION_NANOS
                && Math.abs(signedOrbit) >= 180.0
                && orbitDirectionRatio >= 0.80
                && totalYaw >= 200.0
                && yawRate >= 100.0
                && yawDirectionRatio >= 0.80
                && rapidTurnShare >= 0.45
                && path >= 2.0
                && p90Aim <= MAX_QUANTIZED_P90_ERROR
                && lockRatio >= 0.90
                && aimMean <= 0.70
                && syncPairs >= 20
                && syncMean <= 1.10;

        Mode mode = orbitLock ? Mode.ORBIT_LOCK
                : targetRotationLock ? Mode.TARGET_ROTATION_LOCK : Mode.NONE;
        return new Result(mode, samples.size(), Math.abs(signedOrbit), totalYaw,
                aimMean, maxAim, p90Aim, radiusCv, tangentShare, syncMean,
                lockRatio, path, yawRate, rapidTurnShare,
                yawDirectionRatio, orbitDirectionRatio,
                samples.get(0).frame().createdNanos(),
                samples.get(samples.size() - 1).frame().createdNanos());
    }

    private static double fitFixedAnchor(List<Sample> samples) {
        int fitSamples = Math.max(12, samples.size() / 2);
        double low = 0.0;
        double high = 1.0;
        for (int iteration = 0; iteration < ANCHOR_FIT_ITERATIONS; iteration++) {
            double left = (low * 2.0 + high) / 3.0;
            double right = (low + high * 2.0) / 3.0;
            if (anchorFitScore(samples, fitSamples, left)
                    <= anchorFitScore(samples, fitSamples, right)) {
                high = right;
            } else {
                low = left;
            }
        }
        double fitted = (low + high) * 0.5;
        double fittedScore = anchorFitScore(samples, fitSamples, fitted);
        double centerScore = anchorFitScore(samples, fitSamples, 0.0);
        double eyeScore = anchorFitScore(samples, fitSamples, 1.0);
        if (centerScore <= fittedScore && centerScore <= eyeScore) return 0.0;
        if (eyeScore <= fittedScore) return 1.0;
        return fitted;
    }

    private static double anchorFitScore(List<Sample> samples,
                                         int fitSamples,
                                         double fraction) {
        double score = 0.0;
        for (int i = 0; i < fitSamples; i++) {
            double error = anchorError(samples.get(i), fraction);
            score += error * error;
        }
        return score / fitSamples;
    }

    private static double anchorError(Sample sample, double fraction) {
        return RotationPredictionModel.targetError(
                sample.frame().yaw(), sample.frame().pitch(),
                sample.centerDx() + (sample.eyeDx() - sample.centerDx()) * fraction,
                sample.centerDy() + (sample.eyeDy() - sample.centerDy()) * fraction,
                sample.centerDz() + (sample.eyeDz() - sample.centerDz()) * fraction);
    }

    private static double percentile(double[] values, double percentile) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        int index = Math.max(0, Math.min(copy.length - 1,
                (int) Math.ceil(percentile * copy.length) - 1));
        return copy[index];
    }

    private static List<Sample> longestContinuousSegment(List<Sample> samples) {
        if (samples.size() < 2) return samples;
        int runStart = 0;
        int bestStart = 0;
        int bestEnd = 0;
        for (int index = 1; index <= samples.size(); index++) {
            boolean boundary = index == samples.size();
            if (!boundary) {
                long gap = samples.get(index).frame().createdNanos()
                        - samples.get(index - 1).frame().createdNanos();
                boundary = gap <= 0L || gap > MAX_SAMPLE_GAP_NANOS;
            }
            if (!boundary) continue;

            int runLength = index - runStart;
            int bestLength = bestEnd - bestStart;
            if (runLength > bestLength) {
                bestStart = runStart;
                bestEnd = index;
            }
            runStart = index;
        }
        if (bestStart == 0 && bestEnd == samples.size()) return samples;
        return new ArrayList<>(samples.subList(bestStart, bestEnd));
    }

    private static double wrapDegrees(double degrees) {
        degrees %= 360.0;
        if (degrees >= 180.0) degrees -= 360.0;
        if (degrees < -180.0) degrees += 360.0;
        return degrees;
    }

    enum Mode {
        NONE,
        ORBIT_LOCK,
        TARGET_ROTATION_LOCK
    }

    record Frame(long createdNanos,
                 double eyeX, double eyeY, double eyeZ,
                 float yaw, float pitch) {
        boolean finite() {
            return Double.isFinite(eyeX) && Double.isFinite(eyeY) && Double.isFinite(eyeZ)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }

    record Target(double centerX, double centerY, double centerZ,
                  double eyeX, double eyeY, double eyeZ,
                  double velocityX, double velocityY, double velocityZ,
                  List<TargetPose> trajectory) {
        Target(double centerX, double centerY, double centerZ,
               double eyeX, double eyeY, double eyeZ,
               double velocityX, double velocityY, double velocityZ) {
            this(centerX, centerY, centerZ, eyeX, eyeY, eyeZ,
                    velocityX, velocityY, velocityZ, List.of());
        }

        boolean finite() {
            if (!(Double.isFinite(centerX) && Double.isFinite(centerY) && Double.isFinite(centerZ)
                    && Double.isFinite(eyeX) && Double.isFinite(eyeY) && Double.isFinite(eyeZ)
                    && Double.isFinite(velocityX) && Double.isFinite(velocityY)
                    && Double.isFinite(velocityZ))) return false;
            if (trajectory == null) return false;
            TargetPose previous = null;
            for (TargetPose pose : trajectory) {
                if (pose == null || !pose.finite()) return false;
                if (Math.hypot(pose.velocityX(), pose.velocityZ())
                        > MAX_MEASURED_TARGET_HORIZONTAL_SPEED
                        || Math.abs(pose.velocityY()) > MAX_MEASURED_TARGET_VERTICAL_SPEED) {
                    return false;
                }
                if (previous != null) {
                    long elapsedNanos = pose.createdNanos() - previous.createdNanos();
                    if (elapsedNanos <= 0L) return false;

double elapsedTicks = Math.max(1.0,
                            elapsedNanos / 50_000_000.0);
                    double horizontalSpeed = Math.hypot(
                            pose.centerX() - previous.centerX(),
                            pose.centerZ() - previous.centerZ()) / elapsedTicks;
                    double verticalSpeed = Math.abs(
                            pose.centerY() - previous.centerY()) / elapsedTicks;
                    if (horizontalSpeed > MAX_MEASURED_TARGET_HORIZONTAL_SPEED
                            || verticalSpeed > MAX_MEASURED_TARGET_VERTICAL_SPEED) {
                        return false;
                    }
                }
                previous = pose;
            }
            return true;
        }

        ResolvedPose resolve(long frameNanos, long hitNanos) {
            if (trajectory.size() >= 2) {
                TargetPose first = trajectory.get(0);
                TargetPose last = trajectory.get(trajectory.size() - 1);
                if (frameNanos < first.createdNanos()) {

double elapsedTicks = (first.createdNanos() - frameNanos)
                            / 50_000_000.0;
                    return new ResolvedPose(
                            first.centerX() - first.velocityX() * elapsedTicks,
                            first.centerY() - first.velocityY() * elapsedTicks,
                            first.centerZ() - first.velocityZ() * elapsedTicks,
                            first.eyeX() - first.velocityX() * elapsedTicks,
                            first.eyeY() - first.velocityY() * elapsedTicks,
                            first.eyeZ() - first.velocityZ() * elapsedTicks);
                }
                if (frameNanos > last.createdNanos()) {
                    return null;
                }
                for (int i = 1; i < trajectory.size(); i++) {
                    TargetPose before = trajectory.get(i - 1);
                    TargetPose after = trajectory.get(i);
                    if (frameNanos > after.createdNanos()) continue;
                    long span = after.createdNanos() - before.createdNanos();
                    double alpha = span > 0L
                            ? (frameNanos - before.createdNanos()) / (double) span : 1.0;
                    alpha = Math.max(0.0, Math.min(1.0, alpha));
                    return ResolvedPose.interpolate(before, after, alpha);
                }
                return ResolvedPose.from(last);
            }

double elapsedTicks = (hitNanos - frameNanos) / 50_000_000.0;
            return new ResolvedPose(
                    centerX - velocityX * elapsedTicks,
                    centerY - velocityY * elapsedTicks,
                    centerZ - velocityZ * elapsedTicks,
                    eyeX - velocityX * elapsedTicks,
                    eyeY - velocityY * elapsedTicks,
                    eyeZ - velocityZ * elapsedTicks);
        }
    }

    record TargetPose(long createdNanos,
                      double centerX, double centerY, double centerZ,
                      double eyeX, double eyeY, double eyeZ,
                      double velocityX, double velocityY, double velocityZ) {
        TargetPose(long createdNanos,
                   double centerX, double centerY, double centerZ,
                   double eyeX, double eyeY, double eyeZ) {
            this(createdNanos, centerX, centerY, centerZ,
                    eyeX, eyeY, eyeZ, 0.0, 0.0, 0.0);
        }

        boolean finite() {
            return createdNanos > 0L
                    && Double.isFinite(centerX) && Double.isFinite(centerY)
                    && Double.isFinite(centerZ) && Double.isFinite(eyeX)
                    && Double.isFinite(eyeY) && Double.isFinite(eyeZ)
                    && Double.isFinite(velocityX) && Double.isFinite(velocityY)
                    && Double.isFinite(velocityZ);
        }
    }

    private record ResolvedPose(double centerX, double centerY, double centerZ,
                                double eyeX, double eyeY, double eyeZ) {
        static ResolvedPose from(TargetPose pose) {
            return new ResolvedPose(pose.centerX(), pose.centerY(), pose.centerZ(),
                    pose.eyeX(), pose.eyeY(), pose.eyeZ());
        }

        static ResolvedPose interpolate(TargetPose first, TargetPose second, double alpha) {
            return new ResolvedPose(
                    lerp(first.centerX(), second.centerX(), alpha),
                    lerp(first.centerY(), second.centerY(), alpha),
                    lerp(first.centerZ(), second.centerZ(), alpha),
                    lerp(first.eyeX(), second.eyeX(), alpha),
                    lerp(first.eyeY(), second.eyeY(), alpha),
                    lerp(first.eyeZ(), second.eyeZ(), alpha));
        }

        private static double lerp(double first, double second, double alpha) {
            return first + (second - first) * alpha;
        }
    }

    record Result(Mode mode,
                  int samples,
                  double netOrbit,
                  double totalYaw,
                  double meanAimError,
                  double maxAimError,
                  double p90AimError,
                  double radiusCv,
                  double tangentShare,
                  double syncMean,
                  double lockRatio,
                  double path,
                  double yawRate,
                  double rapidTurnShare,
                  double yawDirectionRatio,
                  double orbitDirectionRatio,
                  long firstFrameNanos,
                  long lastFrameNanos) {
        static Result none() {
            return new Result(Mode.NONE, 0, 0.0, 0.0,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    0.0, Double.POSITIVE_INFINITY, 0.0, 0.0,
                    0.0, 0.0, 0.0, 0.0, 0L, 0L);
        }

        boolean candidate() {
            return mode != Mode.NONE;
        }

        double evidenceRotation() {
            return netOrbit;
        }
    }

    private record Sample(Frame frame,
                          double radius,
                          double centerDx, double centerDy, double centerDz,
                          double eyeDx, double eyeDy, double eyeDz,
                          double orbitAngle,
                          double targetX,
                          double targetZ) {
    }
}
