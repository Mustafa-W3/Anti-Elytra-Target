package com.antielytratarget.check.misc;

final class RotationMismatchEvidence {

    static final int REQUIRED_MISMATCHES = 3;
    static final long WINDOW_TICKS = 40L;

    private long windowStartTick = Long.MIN_VALUE;
    private long lastMismatchTick = Long.MIN_VALUE;
    private long validThroughTick = Long.MIN_VALUE;
    private int count;
    private double maxDelta;

    synchronized Observation record(long packetTick, double delta) {
        if (packetTick <= validThroughTick) {
            return new Observation(false, 0, delta);
        }

        if (windowStartTick == Long.MIN_VALUE
                || packetTick < windowStartTick
                || packetTick - windowStartTick > WINDOW_TICKS) {
            clear();
            windowStartTick = packetTick;
        }

        if (packetTick != lastMismatchTick) {
            lastMismatchTick = packetTick;
            count++;
        }
        maxDelta = Math.max(maxDelta, delta);

        if (count < REQUIRED_MISMATCHES) {
            return new Observation(false, count, maxDelta);
        }

        Observation ready = new Observation(true, count, maxDelta);
        clear();
        return ready;
    }

    synchronized void reset() {
        clear();
        validThroughTick = Long.MIN_VALUE;
    }

    synchronized void recordValid(long packetTick) {
        if (packetTick > validThroughTick) {
            validThroughTick = packetTick;
        }
        clear();
    }

    private void clear() {
        windowStartTick = Long.MIN_VALUE;
        lastMismatchTick = Long.MIN_VALUE;
        count = 0;
        maxDelta = 0.0;
    }

    record Observation(boolean ready, int count, double maxDelta) {
    }
}
