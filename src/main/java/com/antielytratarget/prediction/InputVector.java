package com.antielytratarget.prediction;

public record InputVector(float forward, float strafe, boolean sprint, boolean sneak) {

public static final InputVector NONE = new InputVector(0f, 0f, false, false);

public float magnitude() {
        float mag = forward * forward + strafe * strafe;
        if (mag > 1.0f) {
            float inv = (float) (1.0 / Math.sqrt(mag));
            return inv;
        }
        return 1.0f;
    }

public float normalizedForward() {
        float mag = forward * forward + strafe * strafe;
        if (mag > 1.0f) {
            float inv = (float) (1.0 / Math.sqrt(mag));
            return forward * inv;
        }
        return forward;
    }

public float normalizedStrafe() {
        float mag = forward * forward + strafe * strafe;
        if (mag > 1.0f) {
            float inv = (float) (1.0 / Math.sqrt(mag));
            return strafe * inv;
        }
        return strafe;
    }

public float speedMultiplier() {
        if (sprint && forward > 0) return 1.3f;
        if (sneak) return 0.3f;
        return 1.0f;
    }
}
