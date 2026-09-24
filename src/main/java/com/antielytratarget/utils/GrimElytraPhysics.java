package com.antielytratarget.utils;

import com.antielytratarget.prediction.InputVector;
import com.antielytratarget.prediction.TickState;
import org.bukkit.util.Vector;

public class GrimElytraPhysics {

public static final double GRAVITY = -0.08;

public static final double SLOW_FALLING_GRAVITY = -0.01;

public static final double DRAG_HORIZONTAL = 0.99;
    public static final double DRAG_VERTICAL   = 0.98;

public static final double ELYTRA_AIR_SPEED = 0.02;

public static final double MAX_VELOCITY = 10.0;

public static final double FIREWORK_BOOST_PER_POWER = 0.1;

public static double[] simulate(double velX, double velY, double velZ,
                                    float yawDeg, float pitchDeg) {
        return simulateWithGravity(velX, velY, velZ, yawDeg, pitchDeg, GRAVITY);
    }

public static double[] simulateWithGravity(double velX, double velY, double velZ,
                                               float yawDeg, float pitchDeg,
                                               double gravity) {

        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad   = Math.toRadians(-yawDeg);

double lookX = Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

double horizontalLookLen = Math.sqrt(lookX * lookX + lookZ * lookZ);

        double velHLen = Math.sqrt(velX * velX + velZ * velZ);
        double lookLen = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);

double cosPitch = Math.cos(pitchRad);
        double vertCosRotation = cosPitch * cosPitch * Math.min(1.0, lookLen / 0.4);

double newVelY = velY + gravity * (-1.0 + vertCosRotation * 0.75);

if (newVelY < 0.0 && horizontalLookLen > 0.0) {
            double d5 = newVelY * -0.1 * vertCosRotation;
            velX += lookX * d5 / horizontalLookLen;
            newVelY += d5;
            velZ += lookZ * d5 / horizontalLookLen;
        }

if (pitchRad < 0.0 && horizontalLookLen > 0.0) {
            double d5 = velHLen * (-Math.sin(pitchRad)) * 0.04;
            velX += -lookX * d5 / horizontalLookLen;
            newVelY += d5 * 3.2;
            velZ += -lookZ * d5 / horizontalLookLen;
        }

if (horizontalLookLen > 0.0) {
            velX += (lookX / horizontalLookLen * velHLen - velX) * 0.1;
            velZ += (lookZ / horizontalLookLen * velHLen - velZ) * 0.1;
        }

        return new double[]{ velX, newVelY, velZ };
    }

public static double[] applyDrag(double velX, double velY, double velZ) {
        return new double[]{
                velX * DRAG_HORIZONTAL,
                velY * DRAG_VERTICAL,
                velZ * DRAG_HORIZONTAL
        };
    }

public static double[] applyInputAcceleration(double velX, double velY, double velZ,
                                                   InputVector input, float yawDeg) {
        float forward = input.normalizedForward();
        float strafe = input.normalizedStrafe();

        if (forward == 0 && strafe == 0) {
            return new double[]{velX, velY, velZ};
        }

        double speed = ELYTRA_AIR_SPEED * input.speedMultiplier();
        double yawRad = Math.toRadians(yawDeg);

        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

double inputMag = Math.sqrt(forward * forward + strafe * strafe);
        if (inputMag < 1.0E-7) {
            return new double[]{velX, velY, velZ};
        }

        double factor = speed / Math.max(inputMag, 1.0);
        float scaledForward = (float)(forward * factor);
        float scaledStrafe = (float)(strafe * factor);

        double ax = scaledStrafe * cosYaw - scaledForward * sinYaw;
        double az = scaledForward * cosYaw + scaledStrafe * sinYaw;

        return new double[]{
                velX + ax,
                velY,
                velZ + az
        };
    }

public static double[] applyFireworkBoost(double velX, double velY, double velZ,
                                               float yawDeg, float pitchDeg, int power) {
        if (power <= 0) return new double[]{velX, velY, velZ};

        double pitchRad = Math.toRadians(pitchDeg);
        double yawRad = Math.toRadians(-yawDeg);

        double lookX = Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

double boostX = lookX * 0.1 + (lookX * 1.5 - velX) * 0.5;
        double boostY = lookY * 0.1 + (lookY * 1.5 - velY) * 0.5;
        double boostZ = lookZ * 0.1 + (lookZ * 1.5 - velZ) * 0.5;

        return new double[]{
                velX + boostX,
                velY + boostY,
                velZ + boostZ
        };
    }

public static double[] simulateFullTick(TickState state, InputVector input,
                                            float yaw, float pitch) {
        double gravity = state.hasSlowFalling ? SLOW_FALLING_GRAVITY : GRAVITY;

double[] vel = simulateWithGravity(
                state.velX, state.velY, state.velZ,
                yaw, pitch, gravity);

vel = applyInputAcceleration(vel[0], vel[1], vel[2], input, yaw);

vel = applyDrag(vel[0], vel[1], vel[2]);

if (state.fireworkBoosted) {
            vel = applyFireworkBoost(vel[0], vel[1], vel[2],
                    yaw, pitch, Math.max(1, state.fireworkPower));
        }

vel[0] = clampVelocity(vel[0]);
        vel[1] = clampVelocity(vel[1]);
        vel[2] = clampVelocity(vel[2]);

        return vel;
    }

public static double[] simulateWithDrag(double velX, double velY, double velZ,
                                            float yawDeg, float pitchDeg) {
        double[] afterPhysics = simulate(velX, velY, velZ, yawDeg, pitchDeg);
        return applyDrag(afterPhysics[0], afterPhysics[1], afterPhysics[2]);
    }

public static double velocityOffset(double[] expected, Vector actual) {
        double dx = expected[0] - actual.getX();
        double dy = expected[1] - actual.getY();
        double dz = expected[2] - actual.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

public static double speed(double velX, double velY, double velZ) {
        return Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

private static double clampVelocity(double v) {
        return Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, v));
    }
}