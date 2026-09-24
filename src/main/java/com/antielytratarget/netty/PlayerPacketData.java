package com.antielytratarget.netty;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class PlayerPacketData {

    private static final int ROTATION_SAMPLE_LIMIT = 32;
    private static final int PENDING_USE_ITEM_CONTEXT_LIMIT = 64;
    private static final int PENDING_USE_ITEM_MISMATCH_LIMIT = 64;

public final TransactionTracker transactionTracker = new TransactionTracker();

public final GrimPacketOrderProcessor packetOrderProcessor = new GrimPacketOrderProcessor();

public final GrimTickReliability tickReliability = new GrimTickReliability();

public final SilentFireworkState silentFireworkState = new SilentFireworkState();

public final AtomicLong packetEventsClientTick = new AtomicLong(0);

public final AtomicReference<double[]> lastPosition = new AtomicReference<>(null);

public final AtomicReference<double[]> prevPosition = new AtomicReference<>(null);

public final AtomicReference<float[]> lastRotation = new AtomicReference<>(null);

public final AtomicReference<float[]> prevRotation = new AtomicReference<>(null);

private final ConcurrentLinkedDeque<RotationSample> rotationSamples =
            new ConcurrentLinkedDeque<>();
    private final AtomicLong rotationSequence = new AtomicLong(0L);
    private final AtomicInteger rotationSampleCount = new AtomicInteger(0);

public final AtomicBoolean lastOnGround = new AtomicBoolean(false);

public final AtomicLong movementTick = new AtomicLong(-1);

public final AtomicLong lastMovementPacketTime = new AtomicLong(0);

public final AtomicLong lastRotationPacketTime = new AtomicLong(0);

public final AtomicBoolean hasPositionUpdate = new AtomicBoolean(false);

public final AtomicBoolean hasRotationUpdate = new AtomicBoolean(false);

public final AtomicBoolean didSendMovementBeforeTickEnd = new AtomicBoolean(false);

private final UseItemRotationTracker useItemRotationTracker =
            new UseItemRotationTracker();

private final ConcurrentLinkedDeque<UseItemRotation> pendingUseItemContexts =
            new ConcurrentLinkedDeque<>();

private final AtomicInteger skippedMainHandUseItemContexts = new AtomicInteger();

private final AtomicInteger skippedOffHandUseItemContexts = new AtomicInteger();

private final Object useItemMismatchLock = new Object();

private final ArrayDeque<UseItemRotationMismatch> pendingUseItemMismatches =
            new ArrayDeque<>();

private UseItemRotationMismatch overflowUseItemMismatch;

private boolean useItemMismatchTaskScheduled;

private final ConcurrentLinkedQueue<PendingTeleport> pendingTeleports =
            new ConcurrentLinkedQueue<>();

private final ConcurrentLinkedQueue<PendingCameraState> pendingCameraStates =
            new ConcurrentLinkedQueue<>();

public final AtomicBoolean useItemThisTick = new AtomicBoolean(false);

public final AtomicBoolean cameraEntitySelf = new AtomicBoolean(true);

public final AtomicBoolean slotChangedAfterUse = new AtomicBoolean(false);

public final AtomicInteger heldSlot = new AtomicInteger(-1);

public final AtomicBoolean attackedAfterSlotChange = new AtomicBoolean(false);

public final AtomicLong useItemTick = new AtomicLong(-1);

public final AtomicLong heldChangeTick = new AtomicLong(-1);

public final AtomicReference<Double> sensitivityX = new AtomicReference<>(0.0);
    public final AtomicReference<Double> sensitivityY = new AtomicReference<>(0.0);
    public final AtomicReference<Double> divisorX = new AtomicReference<>(0.0);
    public final AtomicReference<Double> divisorY = new AtomicReference<>(0.0);
    public final AtomicReference<Double> deltaDotsX = new AtomicReference<>(0.0);
    public final AtomicReference<Double> deltaDotsY = new AtomicReference<>(0.0);

public void updatePosition(double x, double y, double z) {
        lastMovementPacketTime.set(System.currentTimeMillis());
        double[] current = lastPosition.get();
        if (current != null) {
            prevPosition.set(current);
        }
        lastPosition.set(new double[]{x, y, z});
    }

public void updateRotation(float yaw, float pitch) {
        long now = System.currentTimeMillis();
        lastMovementPacketTime.set(now);
        lastRotationPacketTime.set(now);
        float[] current = lastRotation.get();
        if (current != null) {
            prevRotation.set(current);
        }
        lastRotation.set(new float[]{yaw, pitch});

        long sequence = rotationSequence.incrementAndGet();
        rotationSamples.addLast(new RotationSample(
                sequence,
                current != null,
                current != null ? current[0] : 0.0f,
                current != null ? current[1] : 0.0f,
                yaw,
                pitch,
                System.nanoTime()));
        int samples = rotationSampleCount.incrementAndGet();
        while (samples > ROTATION_SAMPLE_LIMIT) {
            if (rotationSamples.pollFirst() != null) {
                samples = rotationSampleCount.decrementAndGet();
            } else {
                rotationSampleCount.set(0);
                break;
            }
        }
    }

    public void seedRotation(float yaw, float pitch) {
        float[] rotation = new float[]{yaw, pitch};
        lastRotation.compareAndSet(null, rotation);
        prevRotation.compareAndSet(null, rotation);
    }

    public RotationSample findRotationSampleAfter(long consumedSequence,
                                                   float eventFromYaw,
                                                   float eventFromPitch,
                                                   float eventToYaw,
                                                   float eventToPitch) {
        for (RotationSample sample : rotationSamples) {
            if (sample.sequence() <= consumedSequence || !sample.hasPrevious()) continue;
            if (sameFloat(sample.previousYaw(), eventFromYaw)
                    && sameFloat(sample.previousPitch(), eventFromPitch)
                    && sameFloat(sample.yaw(), eventToYaw)
                    && sameFloat(sample.pitch(), eventToPitch)) {
                return sample;
            }
        }
        return null;
    }

    public long latestRotationSequence() {
        return rotationSequence.get();
    }

    private static boolean sameFloat(float first, float second) {
        return Float.floatToIntBits(first) == Float.floatToIntBits(second);
    }

public void updateOnGround(boolean onGround) {
        lastMovementPacketTime.set(System.currentTimeMillis());
        lastOnGround.set(onGround);
    }

    public long beginPacketEventsTick() {
        long tick = packetEventsClientTick.incrementAndGet();
        resetTick(tick);
        return tick;
    }

    public long currentPacketEventsTick() {
        return packetEventsClientTick.get();
    }

    public void recordUseItem(long tick) {
        useItemThisTick.set(true);
        useItemTick.set(tick);
    }

    public void recordHeldSlot(int slot, long tick) {
        heldSlot.set(slot);
        heldChangeTick.set(tick);
        if (useItemTick.get() == tick) {
            slotChangedAfterUse.set(true);
        }
    }

    public void recordOffhandSwap(long tick) {
        heldChangeTick.set(tick);
        if (useItemTick.get() == tick) {
            slotChangedAfterUse.set(true);
        }
    }

    public void recordAttackAfterSlotChange(long tick) {
        if (heldChangeTick.get() == tick) {
            attackedAfterSlotChange.set(true);
        }
    }

    public TrackedUseItemRotation queueUseItemRotation(float yaw, float pitch, long tick,
                                                       InteractionHand hand,
                                                       Boolean fireworkAtUse,
                                                       float[] currentRotation,
                                                       boolean canSkipTicks) {
        UseItemRotation rotation = new UseItemRotation(yaw, pitch, tick, hand);
        if (fireworkAtUse != null) {
            rotation.resolveFirework(fireworkAtUse);
        }
        UseItemRotationTracker.Decision decision = useItemRotationTracker.onUse(
                rotation, currentRotation, canSkipTicks);
        pendingUseItemContexts.addLast(rotation);
        trimPendingUseItemContexts();
        return new TrackedUseItemRotation(rotation, decision);
    }

    public UseItemRotationTracker.Decision finishUseItemRotations(
            float[] currentRotation,
            float[] previousRotation,
            boolean rotationTickPacket,
            boolean canSkipTicks) {
        return useItemRotationTracker.onTick(
                currentRotation, previousRotation,
                rotationTickPacket, canSkipTicks);
    }

    public void confirmUseItem(InteractionHand hand, boolean firework) {
        if (consumeSkippedUseItemContext(hand)) return;
        for (UseItemRotation rotation : pendingUseItemContexts) {
            if (rotation.hand() == hand && pendingUseItemContexts.remove(rotation)) {
                if (!rotation.isComplete()) {
                    rotation.resolveFirework(firework);
                    rotation.complete();
                }
                return;
            }
        }
    }

    public void completeUseItemRotationBatch(UseItemRotationTracker.Batch batch) {
        if (batch == null) return;
        batch.complete();
        for (UseItemRotation rotation : pendingUseItemContexts) {
            if (rotation.belongsTo(batch)) {
                rotation.complete();
            }
        }
    }

    public void clearUseItemRotations() {
        completeUseItemRotationBatch(useItemRotationTracker.clear());
    }

    public boolean queueUseItemRotationMismatch(
            UseItemRotationTracker.Batch batch,
            float tickYaw,
            float tickPitch) {
        return enqueueUseItemRotationMismatch(
                UseItemRotationMismatch.live(batch, tickYaw, tickPitch));
    }

    public List<UseItemRotationMismatch> drainUseItemRotationMismatches() {
        synchronized (useItemMismatchLock) {
            List<UseItemRotationMismatch> drained = new ArrayList<>(
                    pendingUseItemMismatches.size()
                            + (overflowUseItemMismatch != null ? 1 : 0));
            drained.addAll(pendingUseItemMismatches);
            pendingUseItemMismatches.clear();
            if (overflowUseItemMismatch != null) {
                drained.add(overflowUseItemMismatch);
            }
            overflowUseItemMismatch = null;
            return drained;
        }
    }

    public void deferUseItemRotationMismatch(UseItemRotationMismatch mismatch) {
        enqueueDeferredUseItemRotationMismatch(mismatch.deferred());
    }

    public boolean finishUseItemRotationMismatchDrain() {
        synchronized (useItemMismatchLock) {
            if (!pendingUseItemMismatches.isEmpty()
                    || overflowUseItemMismatch != null) {
                return true;
            }
            useItemMismatchTaskScheduled = false;
            return false;
        }
    }

    public void clearUseItemRotationMismatches() {
        ArrayDeque<UseItemRotationMismatch> cleared;
        synchronized (useItemMismatchLock) {
            cleared = new ArrayDeque<>(pendingUseItemMismatches);
            pendingUseItemMismatches.clear();
            overflowUseItemMismatch = null;
            useItemMismatchTaskScheduled = false;
        }
        for (UseItemRotationMismatch mismatch : cleared) {
            completeUseItemRotationBatch(mismatch.batch());
        }
    }

    int pendingUseItemRotationMismatches() {
        synchronized (useItemMismatchLock) {
            return pendingUseItemMismatches.size()
                    + (overflowUseItemMismatch != null ? 1 : 0);
        }
    }

    private boolean enqueueUseItemRotationMismatch(UseItemRotationMismatch mismatch) {
        UseItemRotationMismatch completed = null;
        boolean schedule;
        synchronized (useItemMismatchLock) {
            if (pendingUseItemMismatches.size()
                    < PENDING_USE_ITEM_MISMATCH_LIMIT) {
                pendingUseItemMismatches.addLast(mismatch);
            } else if (mismatch.unresolvedRotationCount() > 0) {
                completed = removeResolvedUseItemRotationMismatch();
                if (completed != null) {
                    overflowUseItemMismatch = UseItemRotationMismatch.merge(
                            overflowUseItemMismatch, completed);
                    pendingUseItemMismatches.addLast(mismatch);
                } else {
                    overflowUseItemMismatch = UseItemRotationMismatch.merge(
                            overflowUseItemMismatch, mismatch);
                    completed = mismatch;
                }
            } else {
                overflowUseItemMismatch = UseItemRotationMismatch.merge(
                        overflowUseItemMismatch, mismatch);
                completed = mismatch;
            }
            schedule = !useItemMismatchTaskScheduled;
            useItemMismatchTaskScheduled = true;
        }
        if (completed != null) {
            completeUseItemRotationBatch(completed.batch());
        }
        return schedule;
    }

    private void enqueueDeferredUseItemRotationMismatch(
            UseItemRotationMismatch mismatch) {
        UseItemRotationMismatch completed = null;
        synchronized (useItemMismatchLock) {
            if (pendingUseItemMismatches.size()
                    < PENDING_USE_ITEM_MISMATCH_LIMIT) {
                pendingUseItemMismatches.addLast(mismatch);
            } else if (mismatch.unresolvedRotationCount() > 0) {
                completed = removeResolvedUseItemRotationMismatch();
                if (completed != null) {
                    overflowUseItemMismatch = UseItemRotationMismatch.merge(
                            overflowUseItemMismatch, completed);
                    pendingUseItemMismatches.addLast(mismatch);
                } else {
                    overflowUseItemMismatch = UseItemRotationMismatch.merge(
                            overflowUseItemMismatch, mismatch);
                    completed = mismatch;
                }
            } else {
                overflowUseItemMismatch = UseItemRotationMismatch.merge(
                        overflowUseItemMismatch, mismatch);
                completed = mismatch;
            }
        }
        if (completed != null) {
            completeUseItemRotationBatch(completed.batch());
        }
    }

    private UseItemRotationMismatch removeResolvedUseItemRotationMismatch() {
        var iterator = pendingUseItemMismatches.iterator();
        while (iterator.hasNext()) {
            UseItemRotationMismatch pending = iterator.next();
            if (pending.unresolvedRotationCount() == 0) {
                iterator.remove();
                return pending;
            }
        }
        return null;
    }

    int activeUseItemRotations() {
        return useItemRotationTracker.activeRotations();
    }

    int pendingUseItemContexts() {
        return pendingUseItemContexts.size();
    }

    int skippedUseItemContexts(InteractionHand hand) {
        return skippedUseItemContextsFor(hand).get();
    }

    private void trimPendingUseItemContexts() {
        while (pendingUseItemContexts.size() > PENDING_USE_ITEM_CONTEXT_LIMIT) {
            UseItemRotation removed = pendingUseItemContexts.pollFirst();
            if (removed != null) {
                removed.complete();
                incrementCapped(skippedUseItemContextsFor(removed.hand()));
            }
        }
    }

    private boolean consumeSkippedUseItemContext(InteractionHand hand) {
        AtomicInteger skipped = skippedUseItemContextsFor(hand);
        int current;
        do {
            current = skipped.get();
            if (current == 0) return false;
        } while (!skipped.compareAndSet(current, current - 1));
        return true;
    }

    private AtomicInteger skippedUseItemContextsFor(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND
                ? skippedOffHandUseItemContexts
                : skippedMainHandUseItemContexts;
    }

    private static void incrementCapped(AtomicInteger value) {
        int current;
        do {
            current = value.get();
            if (current == Integer.MAX_VALUE) return;
        } while (!value.compareAndSet(current, current + 1));
    }

    public void queueTeleport(double x, double y, double z,
                              float yaw, float pitch,
                              RelativeFlag flags, int transaction) {
        pendingTeleports.add(new PendingTeleport(
                x, y, z, yaw, pitch, flags, transaction));
        while (pendingTeleports.size() > 8) {
            pendingTeleports.poll();
        }
    }

    public boolean checkTeleport(double x, double y, double z,
                                 float yaw, float pitch) {
        PendingTeleport teleport;
        while ((teleport = pendingTeleports.peek()) != null) {
            double[] position = lastPosition.get();
            double baseX = position != null ? position[0] : 0.0;
            double baseY = position != null ? position[1] : 0.0;
            double baseZ = position != null ? position[2] : 0.0;
            double targetX = (teleport.flags().has(RelativeFlag.X) ? baseX : 0.0)
                    + teleport.x();
            double targetY = (teleport.flags().has(RelativeFlag.Y) ? baseY : 0.0)
                    + teleport.y();
            double targetZ = (teleport.flags().has(RelativeFlag.Z) ? baseZ : 0.0)
                    + teleport.z();
            boolean relativePosition = teleport.flags().has(RelativeFlag.X)
                    || teleport.flags().has(RelativeFlag.Y)
                    || teleport.flags().has(RelativeFlag.Z);
            double threshold = relativePosition ? 0.0002 : 0.0;
            boolean rotationsMatch =
                    (yaw == teleport.yaw() || teleport.flags().has(RelativeFlag.YAW))
                            && (pitch == teleport.pitch()
                            || teleport.flags().has(RelativeFlag.PITCH));
            int received = transactionTracker.getLastTransactionReceived();

            if (received == teleport.transaction()
                    && Math.abs(targetX - x) <= threshold
                    && Math.abs(targetY - y) <= 1.0E-7 + threshold
                    && Math.abs(targetZ - z) <= threshold
                    && rotationsMatch) {
                pendingTeleports.poll();
                return true;
            }

            if (received > teleport.transaction()) {
                pendingTeleports.poll();
                continue;
            }

            break;
        }
        return false;
    }

    public void queueCameraState(boolean self, int transaction) {
        pendingCameraStates.add(new PendingCameraState(self, transaction));
        while (pendingCameraStates.size() > 8) {
            pendingCameraStates.poll();
        }
    }

    public void applyTransactionState() {
        int received = transactionTracker.getLastTransactionReceived();
        PendingCameraState state;
        while ((state = pendingCameraStates.peek()) != null
                && received >= state.transaction()) {
            pendingCameraStates.poll();
            cameraEntitySelf.set(state.self());
            if (!state.self()) {
                clearUseItemRotations();
            }
        }
    }

    public void resetPacketState() {
        clearUseItemRotations();
        clearUseItemRotationMismatches();
        UseItemRotation rotation;
        while ((rotation = pendingUseItemContexts.pollFirst()) != null) {
            rotation.complete();
        }
        skippedMainHandUseItemContexts.set(0);
        skippedOffHandUseItemContexts.set(0);
        pendingTeleports.clear();
        pendingCameraStates.clear();
        cameraEntitySelf.set(true);
        packetOrderProcessor.reset();
        tickReliability.reset();
        silentFireworkState.reset();
    }

public double[] computeVelocity() {
        double[] curr = lastPosition.get();
        double[] prev = prevPosition.get();
        if (curr == null || prev == null) return null;
        return new double[]{
                curr[0] - prev[0],
                curr[1] - prev[1],
                curr[2] - prev[2]
        };
    }

public void resetTick(long currentTick) {
        useItemThisTick.set(false);
        slotChangedAfterUse.set(false);
        attackedAfterSlotChange.set(false);
        heldSlot.set(-1);
        hasPositionUpdate.set(false);
        hasRotationUpdate.set(false);

}

    public static final class UseItemRotation {
        private final float yaw;
        private final float pitch;
        private final long tick;
        private final InteractionHand hand;
        private final AtomicReference<Boolean> firework = new AtomicReference<>(null);
        private final AtomicBoolean complete = new AtomicBoolean(false);
        private final AtomicReference<UseItemRotationTracker.Batch> batch =
                new AtomicReference<>(null);

        public UseItemRotation(float yaw, float pitch, long tick, InteractionHand hand) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.tick = tick;
            this.hand = hand;
        }

        public float yaw() {
            return yaw;
        }

        public float pitch() {
            return pitch;
        }

        public long tick() {
            return tick;
        }

        public InteractionHand hand() {
            return hand;
        }

        public boolean fireworkAtUse() {
            return Boolean.TRUE.equals(firework.get());
        }

        public boolean resolveFirework(boolean value) {
            if (!firework.compareAndSet(null, value)) return false;
            UseItemRotationTracker.Batch currentBatch = batch.get();
            if (currentBatch != null && !complete.get()) {
                currentBatch.resolveFirework(value);
            }
            return true;
        }

        boolean isFireworkResolved() {
            return firework.get() != null;
        }

        public boolean isComplete() {
            return complete.get();
        }

        public void complete() {
            if (complete.compareAndSet(false, true) && firework.get() == null) {
                UseItemRotationTracker.Batch currentBatch = batch.get();
                if (currentBatch != null) {
                    currentBatch.resolveUnknown();
                }
            }
        }

        void attach(UseItemRotationTracker.Batch batch) {
            this.batch.set(batch);
            if (fireworkAtUse() && !complete.get()) {
                batch.addFirework();
            }
        }

        boolean belongsTo(UseItemRotationTracker.Batch batch) {
            return this.batch.get() == batch;
        }

    }

    public record TrackedUseItemRotation(
            UseItemRotation rotation,
            UseItemRotationTracker.Decision decision) {}

    public static final class UseItemRotationMismatch {
        private final UseItemRotationTracker.Batch batch;
        private final float useItemYaw;
        private final float useItemPitch;
        private final float tickYaw;
        private final float tickPitch;
        private final long packetTick;
        private final int rotations;
        private final int fireworkRotations;
        private final int attempts;

        private UseItemRotationMismatch(
                UseItemRotationTracker.Batch batch,
                float useItemYaw,
                float useItemPitch,
                float tickYaw,
                float tickPitch,
                long packetTick,
                int rotations,
                int fireworkRotations,
                int attempts) {
            this.batch = batch;
            this.useItemYaw = useItemYaw;
            this.useItemPitch = useItemPitch;
            this.tickYaw = tickYaw;
            this.tickPitch = tickPitch;
            this.packetTick = packetTick;
            this.rotations = rotations;
            this.fireworkRotations = fireworkRotations;
            this.attempts = attempts;
        }

        static UseItemRotationMismatch live(
                UseItemRotationTracker.Batch batch,
                float tickYaw,
                float tickPitch) {
            return new UseItemRotationMismatch(
                    batch, batch.yaw(), batch.pitch(),
                    tickYaw, tickPitch, batch.tick(),
                    0, 0, 0);
        }

        static UseItemRotationMismatch merge(
                UseItemRotationMismatch first,
                UseItemRotationMismatch second) {
            if (first == null) {
                return second.snapshot();
            }
            return new UseItemRotationMismatch(
                    null,
                    second.useItemYaw(), second.useItemPitch(),
                    second.tickYaw(), second.tickPitch(),
                    second.packetTick(),
                    saturatedAdd(first.rotationCount(), second.rotationCount()),
                    saturatedAdd(first.fireworkRotationCount(),
                            second.fireworkRotationCount()),
                    0);
        }

        UseItemRotationMismatch deferred() {
            return new UseItemRotationMismatch(
                    batch, useItemYaw, useItemPitch,
                    tickYaw, tickPitch, packetTick,
                    rotations, fireworkRotations,
                    Math.min(2, attempts + 1));
        }

        UseItemRotationMismatch snapshot() {
            return new UseItemRotationMismatch(
                    null, useItemYaw(), useItemPitch(),
                    tickYaw(), tickPitch(), packetTick(),
                    rotationCount(), fireworkRotationCount(), attempts);
        }

        public UseItemRotationTracker.Batch batch() {
            return batch;
        }

        public float useItemYaw() {
            return useItemYaw;
        }

        public float useItemPitch() {
            return useItemPitch;
        }

        public float tickYaw() {
            return tickYaw;
        }

        public float tickPitch() {
            return tickPitch;
        }

        public long packetTick() {
            return packetTick;
        }

        public int rotationCount() {
            return batch != null ? batch.rotations() : rotations;
        }

        public int fireworkRotationCount() {
            return batch != null
                    ? batch.fireworkRotations()
                    : Math.min(rotations, fireworkRotations);
        }

        public int unresolvedRotationCount() {
            return batch != null ? batch.unresolvedRotations() : 0;
        }

        public int attempts() {
            return attempts;
        }

        private static int saturatedAdd(int first, int second) {
            if (first >= Integer.MAX_VALUE - second) {
                return Integer.MAX_VALUE;
            }
            return first + second;
        }
    }

    public record PendingTeleport(double x, double y, double z,
                                  float yaw, float pitch,
                                  RelativeFlag flags, int transaction) {}

    public record PendingCameraState(boolean self, int transaction) {}

    public record RotationSample(long sequence,
                                 boolean hasPrevious,
                                 float previousYaw,
                                 float previousPitch,
                                 float yaw,
                                 float pitch,
                                 long receivedNanos) {}
}
