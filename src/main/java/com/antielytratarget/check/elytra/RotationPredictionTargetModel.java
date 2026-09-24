package com.antielytratarget.check.elytra;

final class RotationPredictionTargetModel {

    private RotationPredictionTargetModel() {
    }

    static Match match(Frame frame,
                       TargetBox target,
                       double minDistance,
                       double maxDistance,
                       double minPreviousError,
                       double maxLandingError,
                       double minRotation,
                       double maxRotation,
                       double minConvergenceRatio,
                       double maxCorrectionResidual) {
        Match best = null;
        best = better(best, matchPoint(frame,
                (target.minX() + target.maxX()) * 0.5,
                (target.minY() + target.maxY()) * 0.5,
                (target.minZ() + target.maxZ()) * 0.5,
                false, minDistance, maxDistance,
                minPreviousError, maxLandingError, minRotation, maxRotation,
                minConvergenceRatio, maxCorrectionResidual));
        best = better(best, matchPoint(frame,
                target.eyeX(), target.eyeY(), target.eyeZ(),
                false, minDistance, maxDistance,
                minPreviousError, maxLandingError, minRotation, maxRotation,
                minConvergenceRatio, maxCorrectionResidual));

        double[] direction = lookDirection(frame.toYaw(), frame.toPitch());
        double rayDistance = rayBoxDistance(
                frame.eyeX(), frame.eyeY(), frame.eyeZ(), direction,
                target.minX(), target.minY(), target.minZ(),
                target.maxX(), target.maxY(), target.maxZ());
        if (Double.isFinite(rayDistance) && rayDistance >= 0.0) {
            best = better(best, matchPoint(frame,
                    frame.eyeX() + direction[0] * rayDistance,
                    frame.eyeY() + direction[1] * rayDistance,
                    frame.eyeZ() + direction[2] * rayDistance,
                    true, minDistance, maxDistance,
                    minPreviousError, maxLandingError, minRotation, maxRotation,
                    minConvergenceRatio, maxCorrectionResidual));
        }
        return best;
    }

    private static Match matchPoint(Frame frame,
                                    double targetX,
                                    double targetY,
                                    double targetZ,
                                    boolean rayIntersection,
                                    double minDistance,
                                    double maxDistance,
                                    double minPreviousError,
                                    double maxLandingError,
                                    double minRotation,
                                    double maxRotation,
                                    double minConvergenceRatio,
                                    double maxCorrectionResidual) {
        double dx = targetX - frame.eyeX();
        double dy = targetY - frame.eyeY();
        double dz = targetZ - frame.eyeZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance) || distance < minDistance || distance > maxDistance) {
            return null;
        }

        double previousError = RotationPredictionModel.targetError(
                frame.fromYaw(), frame.fromPitch(), dx, dy, dz);
        double currentError = RotationPredictionModel.targetError(
                frame.toYaw(), frame.toPitch(), dx, dy, dz);
        if (!RotationPredictionModel.isGeometryCandidate(
                previousError, currentError, frame.rotation(),
                minPreviousError, maxLandingError,
                minRotation, maxRotation,
                minConvergenceRatio, maxCorrectionResidual)) return null;
        return new Match(currentError, rayIntersection, distance);
    }

    private static Match better(Match first, Match second) {
        if (second == null) return first;
        if (first == null || second.currentError() < first.currentError()) return second;
        return first;
    }

    private static double[] lookDirection(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        return new double[]{
                -horizontal * Math.sin(yawRadians),
                -Math.sin(pitchRadians),
                horizontal * Math.cos(yawRadians)
        };
    }

    private static double rayBoxDistance(double originX, double originY, double originZ,
                                         double[] direction,
                                         double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        double near = 0.0;
        double far = Double.POSITIVE_INFINITY;
        double[] origin = {originX, originY, originZ};
        double[] minimum = {minX, minY, minZ};
        double[] maximum = {maxX, maxY, maxZ};
        for (int axis = 0; axis < 3; axis++) {
            double component = direction[axis];
            if (Math.abs(component) < 1.0E-12) {
                if (origin[axis] < minimum[axis] || origin[axis] > maximum[axis]) {
                    return Double.NaN;
                }
                continue;
            }
            double first = (minimum[axis] - origin[axis]) / component;
            double second = (maximum[axis] - origin[axis]) / component;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) return Double.NaN;
        }
        return far >= 0.0 ? Math.max(0.0, near) : Double.NaN;
    }

    record Frame(double eyeX, double eyeY, double eyeZ,
                 float fromYaw, float fromPitch,
                 float toYaw, float toPitch,
                 double rotation) {
    }

    record TargetBox(double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ,
                     double eyeX, double eyeY, double eyeZ) {
    }

    record Match(double currentError, boolean rayIntersectsBox, double distance) {
    }
}
