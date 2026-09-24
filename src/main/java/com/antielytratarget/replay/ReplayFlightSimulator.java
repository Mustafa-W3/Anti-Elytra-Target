package com.antielytratarget.replay;

import com.antielytratarget.utils.GrimElytraPhysics;

final class ReplayFlightSimulator {

    private static final double HARD_CORRECTION_DISTANCE = 3.0;
    private static final double POSITION_OBSERVATION_WEIGHT = 0.22;
    private static final double VELOCITY_OBSERVATION_WEIGHT = 0.28;

    private boolean initialized;
    private boolean lastGliding;
    private String worldName;
    private double x;
    private double y;
    private double z;
    private double velocityX;
    private double velocityY;
    private double velocityZ;

    Frame advance(ReplaySnapshot snapshot, boolean forceReset) {
        boolean worldChanged = initialized && !sameWorld(worldName, snapshot.getWorldName());
        if (!initialized || forceReset || worldChanged || !snapshot.isGliding() || !lastGliding) {
            reset(snapshot);
            return new Frame(x, y, z, forceReset || worldChanged);
        }

        double[] simulated = GrimElytraPhysics.simulateWithDrag(
                velocityX, velocityY, velocityZ,
                snapshot.getYaw(), snapshot.getPitch());
        if (snapshot.getFireworkPower() > 0) {
            simulated = GrimElytraPhysics.applyFireworkBoost(
                    simulated[0], simulated[1], simulated[2],
                    snapshot.getYaw(), snapshot.getPitch(),
                    snapshot.getFireworkPower());
        }

        double predictedX = x + simulated[0];
        double predictedY = y + simulated[1];
        double predictedZ = z + simulated[2];
        double dx = snapshot.getX() - predictedX;
        double dy = snapshot.getY() - predictedY;
        double dz = snapshot.getZ() - predictedZ;
        double error = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(error) || error > HARD_CORRECTION_DISTANCE) {
            reset(snapshot);
            return new Frame(x, y, z, true);
        }

        x = predictedX + dx * POSITION_OBSERVATION_WEIGHT;
        y = predictedY + dy * POSITION_OBSERVATION_WEIGHT;
        z = predictedZ + dz * POSITION_OBSERVATION_WEIGHT;
        velocityX = blend(simulated[0], snapshot.getVelocityX());
        velocityY = blend(simulated[1], snapshot.getVelocityY());
        velocityZ = blend(simulated[2], snapshot.getVelocityZ());
        lastGliding = true;
        return new Frame(x, y, z, false);
    }

    void reset() {
        initialized = false;
    }

    private void reset(ReplaySnapshot snapshot) {
        initialized = true;
        lastGliding = snapshot.isGliding();
        worldName = snapshot.getWorldName();
        x = snapshot.getX();
        y = snapshot.getY();
        z = snapshot.getZ();
        velocityX = finiteOrZero(snapshot.getVelocityX());
        velocityY = finiteOrZero(snapshot.getVelocityY());
        velocityZ = finiteOrZero(snapshot.getVelocityZ());
    }

    private static double blend(double simulated, double observed) {
        double safeObserved = finiteOrZero(observed);
        return simulated * (1.0 - VELOCITY_OBSERVATION_WEIGHT)
                + safeObserved * VELOCITY_OBSERVATION_WEIGHT;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static boolean sameWorld(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    record Frame(double x, double y, double z, boolean hardCorrection) {
    }
}
