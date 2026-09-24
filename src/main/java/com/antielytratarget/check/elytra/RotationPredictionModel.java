package com.antielytratarget.check.elytra;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

final class RotationPredictionModel {

    private static final double EPSILON = 1.0E-12;

    private RotationPredictionModel() {
    }

    static double wrappedYawDelta(float from, float to) {
        double delta = (double) to - (double) from;
        delta %= 360.0;
        if (delta >= 180.0) delta -= 360.0;
        if (delta < -180.0) delta += 360.0;
        return delta;
    }

    static double qualifyingRapidYaw(float from, float to, double minimumYaw) {
        if (!Float.isFinite(from) || !Float.isFinite(to)
                || !Double.isFinite(minimumYaw)) return 0.0;
        double threshold = Math.max(0.0, Math.min(180.0, minimumYaw));
        double turn = Math.abs(wrappedYawDelta(from, to));
        return turn + EPSILON >= threshold ? turn : 0.0;
    }

    static boolean hasRepresentationJump(float from, float to, double toleranceDegrees) {
        double raw = (double) to - (double) from;
        double wrapped = wrappedYawDelta(from, to);
        return Math.abs(Math.abs(raw) - Math.abs(wrapped)) > toleranceDegrees;
    }

    static double geodesicRotation(float fromYaw, float fromPitch,
                                     float toYaw, float toPitch) {
        double fromYawRadians = Math.toRadians(fromYaw);
        double fromPitchRadians = Math.toRadians(fromPitch);
        double toYawRadians = Math.toRadians(toYaw);
        double toPitchRadians = Math.toRadians(toPitch);

        double fromHorizontal = Math.cos(fromPitchRadians);
        double toHorizontal = Math.cos(toPitchRadians);
        double dot = fromHorizontal * toHorizontal
                * (Math.sin(fromYawRadians) * Math.sin(toYawRadians)
                + Math.cos(fromYawRadians) * Math.cos(toYawRadians))
                + Math.sin(fromPitchRadians) * Math.sin(toPitchRadians);
        return safeAcosDegrees(dot);
    }

    static double targetError(float yaw, float pitch, double x, double y, double z) {
        double targetLength = Math.sqrt(x * x + y * y + z * z);
        if (targetLength <= EPSILON) return 180.0;

        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        double lookX = -horizontal * Math.sin(yawRadians);
        double lookY = -Math.sin(pitchRadians);
        double lookZ = horizontal * Math.cos(yawRadians);
        double dot = (lookX * x + lookY * y + lookZ * z) / targetLength;
        return safeAcosDegrees(dot);
    }

    static boolean isGeometryCandidate(double previousError,
                                       double currentError,
                                       double geodesicRotation,
                                       double minPreviousError,
                                       double maxCurrentError,
                                       double minRotation,
                                       double maxRotation,
                                       double minConvergenceRatio,
                                       double maxCorrectionResidual) {
        if (!Double.isFinite(previousError) || !Double.isFinite(currentError)
                || !Double.isFinite(geodesicRotation)) {
            return false;
        }
        if (previousError < minPreviousError || currentError > maxCurrentError) return false;
        if (geodesicRotation < minRotation || geodesicRotation > maxRotation) return false;

        double improvement = previousError - currentError;
        if (improvement <= 0.0) return false;

        double convergenceRatio = improvement / Math.max(geodesicRotation, EPSILON);
        double correctionResidual = Math.abs(improvement - geodesicRotation);
        return convergenceRatio >= minConvergenceRatio
                && correctionResidual <= maxCorrectionResidual;
    }

    static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length <= EPSILON) return new double[]{0.0, 0.0, 0.0};
        return new double[]{x / length, y / length, z / length};
    }

    static double angle(double[] first, double[] second) {
        double firstLength = Math.sqrt(first[0] * first[0]
                + first[1] * first[1] + first[2] * first[2]);
        double secondLength = Math.sqrt(second[0] * second[0]
                + second[1] * second[1] + second[2] * second[2]);
        if (firstLength <= EPSILON || secondLength <= EPSILON) return 180.0;
        double dot = (first[0] * second[0] + first[1] * second[1]
                + first[2] * second[2]) / (firstLength * secondLength);
        return safeAcosDegrees(dot);
    }

    private static double safeAcosDegrees(double value) {
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, value))));
    }

    static final class LatticeTracker {
        static final double MIN_MOUSE_STEP = 0.0096;
        static final double MAX_MOUSE_STEP = 0.6144;

        private static final int CAPACITY = 80;
        private static final int MIN_RELIABLE_SAMPLES = 60;
        private static final int MAX_ANCHORS = 8;
        private static final double MIN_FIT_RATIO = 0.975;
        private static final double GENERIC_MAX_DELTA = 5.0;
        private static final double MIN_USABLE_DELTA = 0.004;
        private static final double COARSE_RELATIVE_RESIDUAL = 0.03;
        private static final double INTERVAL_NUMERIC_PADDING = 1.0E-9;

        private final Deque<CalibrationEntry> entries = new ArrayDeque<>(CAPACITY);
        private long revision;
        private long epoch = 1L;
        private long cachedRevision = -1L;
        private Calibration cachedCalibration;

        Observation observe(float fromYaw, float fromPitch,
                            float toYaw, float toPitch) {
            AxisObservation yaw = axis(
                    Math.abs(wrappedYawDelta(fromYaw, toYaw)), fromYaw, toYaw);
            AxisObservation pitch = axis(
                    Math.abs((double) toPitch - (double) fromPitch), fromPitch, toPitch);

            boolean smallPacket = yaw.delta() <= GENERIC_MAX_DELTA
                    && pitch.delta() <= GENERIC_MAX_DELTA;
            boolean usable = false;
            if (smallPacket) {
                if (yaw.usable()) {
                    add(yaw);
                    usable = true;
                }
                if (pitch.usable()) {
                    add(pitch);
                    usable = true;
                }
            }
            return new Observation(usable, yaw, pitch);
        }

        SnapEvidence findMismatch(float fromYaw, float fromPitch,
                                  float toYaw, float toPitch) {
            Calibration calibration = calibration();
            if (calibration == null) return null;

            AxisObservation yaw = axis(
                    Math.abs(wrappedYawDelta(fromYaw, toYaw)), fromYaw, toYaw);
            AxisObservation pitch = axis(
                    Math.abs((double) toPitch - (double) fromPitch), fromPitch, toPitch);
            Observation snap = new Observation(yaw.usable() || pitch.usable(), yaw, pitch);
            if (!snap.usable()) return null;

            for (int divisor = calibration.minDivisor();
                 divisor <= calibration.maxDivisor(); divisor++) {
                if (calibration.admits(snap, divisor)) return null;
            }
            return new SnapEvidence(epoch, calibration);
        }

        Calibration calibration() {
            if (cachedRevision == revision) return cachedCalibration;
            cachedRevision = revision;
            cachedCalibration = calculateCalibration();
            return cachedCalibration;
        }

        int size() {
            return entries.size();
        }

        long epoch() {
            return epoch;
        }

        void reset() {
            entries.clear();
            revision++;
            epoch++;
            cachedRevision = -1L;
            cachedCalibration = null;
        }

        private Calibration calculateCalibration() {
            if (entries.size() < MIN_RELIABLE_SAMPLES) return null;

            List<CalibrationEntry> samples = new ArrayList<>(entries);
            List<CalibrationEntry> anchors = new ArrayList<>(samples);
            anchors.sort(Comparator.comparingDouble(CalibrationEntry::delta).reversed());
            if (anchors.size() > MAX_ANCHORS) {
                anchors = anchors.subList(0, MAX_ANCHORS);
            }

            Fit best = null;
            for (CalibrationEntry anchor : anchors) {
                int maximumCount = (int) Math.floor(
                        (anchor.delta() + anchor.error()) / MIN_MOUSE_STEP);
                for (int count = 1; count <= maximumCount; count++) {
                    double candidate = anchor.delta() / count;
                    if (candidate < MIN_MOUSE_STEP || candidate > GENERIC_MAX_DELTA) continue;
                    if (best != null && candidate <= best.center()) continue;

                    Fit fit = fit(samples, candidate, anchor.error() / count);
                    if (fit != null && (best == null || fit.center() > best.center())) {
                        best = fit;
                    }
                }
            }
            if (best == null) return null;

            double lower = Math.max(EPSILON, best.lower());
            double upper = best.upper();
            int minDivisor = Math.max(1, (int) Math.ceil(lower / MAX_MOUSE_STEP));
            int maxDivisor = (int) Math.floor(upper / MIN_MOUSE_STEP);
            if (minDivisor > maxDivisor) return null;
            return new Calibration(epoch, lower, upper, minDivisor, maxDivisor,
                    best.fitCount(), samples.size());
        }

        private Fit fit(List<CalibrationEntry> samples,
                        double initial,
                        double initialUncertainty) {
            double center = initial;
            int required = (int) Math.ceil(samples.size() * MIN_FIT_RATIO);

            for (int iteration = 0; iteration < 3; iteration++) {
                double numerator = 0.0;
                double denominator = 0.0;
                int fitted = 0;
                for (CalibrationEntry entry : samples) {
                    int count = nearestPositiveCount(entry.delta(), center);
                    if (count <= 0) continue;
                    double residual = Math.abs(entry.delta() - count * center);
                    double tolerance = entry.error()
                            + count * initialUncertainty
                            + COARSE_RELATIVE_RESIDUAL * center;
                    if (residual > tolerance) continue;
                    numerator += count * entry.delta();
                    denominator += (double) count * count;
                    fitted++;
                }
                if (fitted < required || denominator <= 0.0) return null;
                center = numerator / denominator;
                if (!Double.isFinite(center) || center < MIN_MOUSE_STEP
                        || center > GENERIC_MAX_DELTA) return null;
            }

            double[] lowers = new double[samples.size()];
            double[] uppers = new double[samples.size()];
            int fitted = 0;
            for (CalibrationEntry entry : samples) {
                int count = nearestPositiveCount(entry.delta(), center);
                if (count <= 0) continue;
                double stepEstimate = entry.delta() / count;
                double stepError = entry.error() / count;
                double deviation = Math.abs(stepEstimate - center);
                if (deviation > COARSE_RELATIVE_RESIDUAL * center + stepError) continue;
                double padding = Math.max(INTERVAL_NUMERIC_PADDING, center * 1.0E-8);
                lowers[fitted] = Math.max(EPSILON, stepEstimate - stepError - padding);
                uppers[fitted] = stepEstimate + stepError + padding;
                fitted++;
            }
            if (fitted < required) return null;

            double[] overlap = requiredOverlap(lowers, uppers, fitted, required);
            if (overlap == null) return null;
            double overlapCenter = (overlap[0] + overlap[1]) * 0.5;
            return new Fit(overlapCenter, overlap[0], overlap[1], fitted);
        }

        private static double[] requiredOverlap(double[] lowers,
                                                double[] uppers,
                                                int size,
                                                int required) {
            java.util.Arrays.sort(lowers, 0, size);
            java.util.Arrays.sort(uppers, 0, size);

            int lowerIndex = 0;
            int upperIndex = 0;
            int active = 0;
            double regionStart = Double.NaN;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;

            while (lowerIndex < size || upperIndex < size) {
                boolean enter = lowerIndex < size
                        && (upperIndex >= size || lowers[lowerIndex] <= uppers[upperIndex]);
                if (enter) {
                    active++;
                    if (active == required) regionStart = lowers[lowerIndex];
                    lowerIndex++;
                } else {
                    if (active == required && Double.isFinite(regionStart)) {
                        minimum = Math.min(minimum, regionStart);
                        maximum = Math.max(maximum, uppers[upperIndex]);
                        regionStart = Double.NaN;
                    }
                    active--;
                    upperIndex++;
                }
            }
            if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
                return null;
            }
            return new double[]{minimum, maximum};
        }

        private void add(AxisObservation observation) {
            entries.addLast(new CalibrationEntry(observation.delta(), observation.error()));
            while (entries.size() > CAPACITY) entries.removeFirst();
            revision++;
        }

        private static AxisObservation axis(double delta, float from, float to) {
            double error = Math.max(1.0E-7,
                    16.0 * ((double) Math.ulp(from) + (double) Math.ulp(to)));
            boolean usable = Double.isFinite(delta) && delta >= MIN_USABLE_DELTA;
            return new AxisObservation(usable, delta, error);
        }

        private static int nearestPositiveCount(double delta, double step) {
            if (step <= 0.0) return -1;
            long rounded = Math.round(delta / step);
            return rounded > 0L && rounded <= Integer.MAX_VALUE ? (int) rounded : -1;
        }
    }

    record Observation(boolean usable,
                       AxisObservation yaw,
                       AxisObservation pitch) {
    }

    record AxisObservation(boolean usable, double delta, double error) {
    }

    record SnapEvidence(long epoch, Calibration calibration) {
    }

    static final class Calibration {
        private final long epoch;
        private final double observedLower;
        private final double observedUpper;
        private final int minDivisor;
        private final int maxDivisor;
        private final int fitCount;
        private final int sampleCount;

        private Calibration(long epoch,
                            double observedLower,
                            double observedUpper,
                            int minDivisor,
                            int maxDivisor,
                            int fitCount,
                            int sampleCount) {
            this.epoch = epoch;
            this.observedLower = observedLower;
            this.observedUpper = observedUpper;
            this.minDivisor = minDivisor;
            this.maxDivisor = maxDivisor;
            this.fitCount = fitCount;
            this.sampleCount = sampleCount;
        }

        long epoch() {
            return epoch;
        }

        int minDivisor() {
            return minDivisor;
        }

        int maxDivisor() {
            return maxDivisor;
        }

        int fitCount() {
            return fitCount;
        }

        int sampleCount() {
            return sampleCount;
        }

        double center() {
            return (observedLower + observedUpper) * 0.5;
        }

        double lower() {
            return observedLower;
        }

        double upper() {
            return observedUpper;
        }

        boolean admits(Observation observation, int divisor) {
            if (divisor < minDivisor || divisor > maxDivisor || !observation.usable()) {
                return false;
            }

            double lower = Math.max(LatticeTracker.MIN_MOUSE_STEP, observedLower / divisor);
            double upper = Math.min(LatticeTracker.MAX_MOUSE_STEP, observedUpper / divisor);
            if (lower > upper) return false;

            if (observation.yaw().usable()
                    && !axisAdmits(observation.yaw(), lower, upper)) return false;
            return !observation.pitch().usable()
                    || axisAdmits(observation.pitch(), lower, upper);
        }

        private static boolean axisAdmits(AxisObservation axis,
                                          double lowerStep,
                                          double upperStep) {
            double minimumDelta = Math.max(0.0, axis.delta() - axis.error());
            double maximumDelta = axis.delta() + axis.error();
            long minimumCount = Math.max(1L,
                    (long) Math.ceil(minimumDelta / upperStep - 1.0E-9));
            long maximumCount = (long) Math.floor(maximumDelta / lowerStep + 1.0E-9);
            return minimumCount <= maximumCount;
        }
    }

    private record CalibrationEntry(double delta, double error) {
    }

    private record Fit(double center, double lower, double upper, int fitCount) {
    }
}
