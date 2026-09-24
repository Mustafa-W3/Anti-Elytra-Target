package com.antielytratarget.netty;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class UseItemRotationTracker {

    private Batch active;

    synchronized Decision onUse(PlayerPacketData.UseItemRotation rotation,
                                float[] currentRotation,
                                boolean canSkipTicks) {
        Decision decision = null;
        if (active != null && !active.matches(rotation.yaw(), rotation.pitch())) {
            boolean valid = currentRotation == null
                    || canSkipTicks && active.matches(currentRotation);
            decision = new Decision(
                    active,
                    valid,
                    currentRotation != null ? currentRotation[0] : 0.0f,
                    currentRotation != null ? currentRotation[1] : 0.0f);
            active = null;
        }

        if (active == null) {
            active = new Batch(rotation.yaw(), rotation.pitch(), rotation.tick());
        }
        active.add(rotation);
        return decision;
    }

    synchronized Decision onTick(float[] currentRotation,
                                 float[] previousRotation,
                                 boolean rotationTickPacket,
                                 boolean canSkipTicks) {
        if (active == null) return null;

        Batch completed = active;
        active = null;
        boolean valid = currentRotation == null
                || completed.matches(currentRotation)
                || canSkipTicks && rotationTickPacket
                && previousRotation != null
                && completed.matches(previousRotation);
        return new Decision(
                completed,
                valid,
                currentRotation != null ? currentRotation[0] : 0.0f,
                currentRotation != null ? currentRotation[1] : 0.0f);
    }

    synchronized Batch clear() {
        Batch completed = active;
        active = null;
        return completed;
    }

    synchronized int activeRotations() {
        return active != null ? active.rotations() : 0;
    }

    record Decision(Batch batch, boolean valid, float tickYaw, float tickPitch) {
    }

    static final class Batch {
        private final float yaw;
        private final float pitch;
        private final long tick;
        private final AtomicInteger rotations = new AtomicInteger();
        private final AtomicInteger fireworkRotations = new AtomicInteger();
        private final AtomicInteger unresolvedRotations = new AtomicInteger();
        private final AtomicBoolean complete = new AtomicBoolean();

        private Batch(float yaw, float pitch, long tick) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.tick = tick;
        }

        private void add(PlayerPacketData.UseItemRotation rotation) {
            if (!increment(rotations)) {
                rotation.complete();
                return;
            }
            if (!rotation.isFireworkResolved()) {
                increment(unresolvedRotations);
            }
            rotation.attach(this);
        }

        void addFirework() {
            if (!complete.get()) {
                increment(fireworkRotations);
            }
        }

        void resolveFirework(boolean firework) {
            decrement(unresolvedRotations);
            if (firework) {
                addFirework();
            }
        }

        void resolveUnknown() {
            decrement(unresolvedRotations);
        }

        boolean matches(float[] rotation) {
            return rotation != null && matches(rotation[0], rotation[1]);
        }

        boolean matches(float yaw, float pitch) {
            return this.yaw == yaw && this.pitch == pitch;
        }

        float yaw() {
            return yaw;
        }

        float pitch() {
            return pitch;
        }

        long tick() {
            return tick;
        }

        int rotations() {
            return rotations.get();
        }

        int fireworkRotations() {
            return Math.min(rotations(), fireworkRotations.get());
        }

        int unresolvedRotations() {
            return unresolvedRotations.get();
        }

        boolean isComplete() {
            return complete.get();
        }

        void complete() {
            complete.set(true);
        }

        private static boolean increment(AtomicInteger value) {
            int current;
            do {
                current = value.get();
                if (current == Integer.MAX_VALUE) return false;
            } while (!value.compareAndSet(current, current + 1));
            return true;
        }

        private static void decrement(AtomicInteger value) {
            int current;
            do {
                current = value.get();
                if (current == 0) return;
            } while (!value.compareAndSet(current, current - 1));
        }
    }
}
