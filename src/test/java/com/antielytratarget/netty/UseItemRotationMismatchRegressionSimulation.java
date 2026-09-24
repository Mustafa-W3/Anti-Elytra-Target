package com.antielytratarget.netty;

import com.antielytratarget.utils.ClientVersionExemptions;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;

public final class UseItemRotationMismatchRegressionSimulation {

    private UseItemRotationMismatchRegressionSimulation() {
    }

    public static void main(String[] args) {
        verifyTickSkippingRules();
        verifyTickPacketRules();
        verifyDuplicateMovementRules();
        verifyVanillaAttackThenFireworkRotation();
        verifyClientVersionExemptions();
        verifyUseItemContext();
        verifySameRotationGrouping();
        verifyImmediateRotationTransitions();
        verifyTickRotationMatching();
        verifyFireworkContextOrdering();
        verifyContextBound();
        verifyMismatchDrainBound();
        verifyMismatchOverflowKeepsUnresolved();
        verifyDeferredFireworkResolution();
        verifyTeleportCompensation();
        verifyCameraCompensation();
        System.out.println("Use-item rotation mismatch regression simulations passed.");
    }

    private static void verifyTickSkippingRules() {
        require(GrimPacketEventsListener.canSkipTicks(
                ClientVersion.V_1_21, ServerVersion.V_1_21));
        require(!GrimPacketEventsListener.canSkipTicks(
                ClientVersion.V_1_21_2, ServerVersion.V_1_21_2));
        require(GrimPacketEventsListener.canSkipTicks(
                ClientVersion.V_1_21_2, ServerVersion.V_1_21));
        require(!GrimPacketEventsListener.canSkipTicks(
                ClientVersion.V_1_8, ServerVersion.V_1_21));
    }

    private static void verifyTickPacketRules() {
        require(GrimPacketEventsListener.isTickPacket(
                true, false, true, false, false, false));
        require(!GrimPacketEventsListener.isTickPacket(
                true, true, true, false, false, false));
        require(GrimPacketEventsListener.isTickPacket(
                true, false, false, true, false, false));
        require(!GrimPacketEventsListener.isTickPacket(
                true, false, false, true, true, false));
        require(!GrimPacketEventsListener.isTickPacket(
                true, false, false, true, false, true));
    }

    private static void verifyDuplicateMovementRules() {
        require(GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_20_5,
                false, true, true, false,
                true, true, 0.00000001));
        require(!GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_21,
                false, true, true, false,
                true, true, 0.00000001));
        require(!GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_16_4,
                false, true, true, false,
                true, true, 0.00000001));
        require(!GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_20_5,
                true, true, true, false,
                true, true, 0.00000001));
        require(!GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_20_5,
                false, true, false, false,
                true, true, 0.00000001));
        require(!GrimPacketEventsListener.isDuplicateMovement(
                ClientVersion.V_1_21_11,
                false, true, true, false,
                true, true, 0.0));
    }

    private static void verifyVanillaAttackThenFireworkRotation() {
        PlayerPacketData data = new PlayerPacketData();
        data.seedRotation(10.0f, 1.0f);

        data.recordAttackAfterSlotChange(100L);
        data.queueUseItemRotation(
                18.6140f, 3.0f, 100L, InteractionHand.MAIN_HAND,
                true, rotation(10.0f, 1.0f), false);
        UseItemRotationTracker.Decision decision =
                data.finishUseItemRotations(
                        rotation(18.6140f, 3.0f),
                        rotation(10.0f, 1.0f), true, false);
        require(decision != null && decision.valid());
    }

    private static void verifyClientVersionExemptions() {
        require(ClientVersionExemptions.isPacketOrderEExempt(ClientVersion.V_1_21_7));
        require(!ClientVersionExemptions.isPacketOrderEExempt(ClientVersion.V_1_21_6));
        require(!ClientVersionExemptions.isPacketOrderEExempt(ClientVersion.V_1_21_9));
        require(ClientVersionExemptions.isUseItemRotationMismatchExempt(ClientVersion.V_1_16_4));
        require(!ClientVersionExemptions.isUseItemRotationMismatchExempt(ClientVersion.V_1_16_3));
        require(!ClientVersionExemptions.isUseItemRotationMismatchExempt(ClientVersion.V_1_17));
    }

    private static void verifyUseItemContext() {
        PlayerPacketData data = new PlayerPacketData();
        PlayerPacketData.TrackedUseItemRotation tracked = data.queueUseItemRotation(
                45.0f, 10.0f, 12L, InteractionHand.OFF_HAND,
                null, rotation(45.0f, 10.0f), true);
        PlayerPacketData.UseItemRotation rotation = tracked.rotation();

        data.confirmUseItem(InteractionHand.MAIN_HAND, true);
        require(!rotation.fireworkAtUse());
        data.confirmUseItem(InteractionHand.OFF_HAND, true);
        require(rotation.fireworkAtUse());
        data.resetPacketState();
        require(rotation.isComplete());
    }

    private static void verifySameRotationGrouping() {
        PlayerPacketData invalid = new PlayerPacketData();
        for (int i = 0; i < 12; i++) {
            PlayerPacketData.TrackedUseItemRotation tracked =
                    invalid.queueUseItemRotation(
                            20.0f, 5.0f, 4L, InteractionHand.MAIN_HAND,
                            true, rotation(0.0f, 0.0f), false);
            require(tracked.decision() == null);
        }
        require(invalid.activeUseItemRotations() == 12);
        UseItemRotationTracker.Decision invalidTick =
                invalid.finishUseItemRotations(
                        rotation(40.0f, 5.0f), null, false, false);
        require(invalidTick != null && !invalidTick.valid());
        require(invalidTick.batch().rotations() == 12);
        require(invalidTick.batch().fireworkRotations() == 12);

        PlayerPacketData valid = new PlayerPacketData();
        for (int i = 0; i < 12; i++) {
            valid.queueUseItemRotation(
                    20.0f, 5.0f, 4L, InteractionHand.MAIN_HAND,
                    true, rotation(0.0f, 0.0f), false);
        }
        UseItemRotationTracker.Decision validTick =
                valid.finishUseItemRotations(
                        rotation(20.0f, 5.0f), null, false, false);
        require(validTick != null && validTick.valid());
        require(validTick.batch().rotations() == 12);
    }

    private static void verifyImmediateRotationTransitions() {
        PlayerPacketData noSkip = new PlayerPacketData();
        noSkip.queueUseItemRotation(
                10.0f, 1.0f, 1L, InteractionHand.MAIN_HAND,
                true, rotation(10.0f, 1.0f), false);
        noSkip.queueUseItemRotation(
                10.0f, 1.0f, 1L, InteractionHand.OFF_HAND,
                true, rotation(10.0f, 1.0f), false);
        UseItemRotationTracker.Decision noSkipTransition =
                noSkip.queueUseItemRotation(
                        20.0f, 2.0f, 1L, InteractionHand.MAIN_HAND,
                        true, rotation(10.0f, 1.0f), false).decision();
        require(noSkipTransition != null && !noSkipTransition.valid());
        require(noSkipTransition.batch().matches(10.0f, 1.0f));
        require(noSkipTransition.batch().rotations() == 2);
        require(noSkip.finishUseItemRotations(
                rotation(20.0f, 2.0f), null, false, false).valid());

        PlayerPacketData skip = new PlayerPacketData();
        skip.queueUseItemRotation(
                10.0f, 1.0f, 1L, InteractionHand.MAIN_HAND,
                true, rotation(10.0f, 1.0f), true);
        UseItemRotationTracker.Decision skippedTransition =
                skip.queueUseItemRotation(
                        20.0f, 2.0f, 1L, InteractionHand.MAIN_HAND,
                        true, rotation(10.0f, 1.0f), true).decision();
        require(skippedTransition != null && skippedTransition.valid());

        UseItemRotationTracker.Decision middleTransition =
                skip.queueUseItemRotation(
                        30.0f, 3.0f, 1L, InteractionHand.MAIN_HAND,
                        true, rotation(10.0f, 1.0f), true).decision();
        require(middleTransition != null && !middleTransition.valid());
        require(middleTransition.batch().matches(20.0f, 2.0f));
        require(skip.finishUseItemRotations(
                rotation(30.0f, 3.0f), null, false, true).valid());

        PlayerPacketData wrongCurrent = new PlayerPacketData();
        wrongCurrent.queueUseItemRotation(
                10.0f, 1.0f, 1L, InteractionHand.MAIN_HAND,
                true, rotation(40.0f, 4.0f), true);
        UseItemRotationTracker.Decision wrongTransition =
                wrongCurrent.queueUseItemRotation(
                        20.0f, 2.0f, 1L, InteractionHand.MAIN_HAND,
                        true, rotation(40.0f, 4.0f), true).decision();
        require(wrongTransition != null && !wrongTransition.valid());
    }

    private static void verifyTickRotationMatching() {
        require(tickDecision(
                rotation(10.0f, 1.0f), null, false, false).valid());
        require(tickDecision(
                rotation(20.0f, 2.0f), rotation(10.0f, 1.0f),
                true, true).valid());
        require(!tickDecision(
                rotation(20.0f, 2.0f), rotation(10.0f, 1.0f),
                false, true).valid());
        require(!tickDecision(
                rotation(20.0f, 2.0f), rotation(10.0f, 1.0f),
                true, false).valid());
        require(!tickDecision(
                rotation(20.0f, 2.0f), rotation(30.0f, 3.0f),
                true, true).valid());
    }

    private static void verifyFireworkContextOrdering() {
        PlayerPacketData ordered = new PlayerPacketData();
        ordered.queueUseItemRotation(
                20.0f, 2.0f, 1L, InteractionHand.MAIN_HAND,
                false, rotation(10.0f, 1.0f), true);
        UseItemRotationTracker.Decision transition =
                ordered.queueUseItemRotation(
                        10.0f, 1.0f, 1L, InteractionHand.OFF_HAND,
                        true, rotation(10.0f, 1.0f), true).decision();
        require(transition != null && !transition.valid());
        require(transition.batch().matches(20.0f, 2.0f));
        require(transition.batch().fireworkRotations() == 0);
        UseItemRotationTracker.Decision tick = ordered.finishUseItemRotations(
                rotation(20.0f, 2.0f), rotation(10.0f, 1.0f),
                true, true);
        require(tick != null && tick.valid());
        require(tick.batch().matches(10.0f, 1.0f));
        require(tick.batch().fireworkRotations() == 1);

        PlayerPacketData fifo = new PlayerPacketData();
        fifo.queueUseItemRotation(
                15.0f, 3.0f, 2L, InteractionHand.MAIN_HAND,
                null, rotation(0.0f, 0.0f), false);
        fifo.queueUseItemRotation(
                15.0f, 3.0f, 2L, InteractionHand.MAIN_HAND,
                true, rotation(0.0f, 0.0f), false);
        fifo.queueUseItemRotation(
                15.0f, 3.0f, 2L, InteractionHand.OFF_HAND,
                false, rotation(0.0f, 0.0f), false);
        fifo.confirmUseItem(InteractionHand.MAIN_HAND, false);
        fifo.confirmUseItem(InteractionHand.MAIN_HAND, true);
        fifo.confirmUseItem(InteractionHand.OFF_HAND, false);
        UseItemRotationTracker.Decision fifoTick = fifo.finishUseItemRotations(
                rotation(30.0f, 6.0f), null, false, false);
        require(fifoTick != null && !fifoTick.valid());
        require(fifoTick.batch().rotations() == 3);
        require(fifoTick.batch().fireworkRotations() == 1);
    }

    private static void verifyContextBound() {
        PlayerPacketData data = new PlayerPacketData();
        for (int i = 0; i < 100; i++) {
            data.queueUseItemRotation(
                    25.0f, 5.0f, 3L, InteractionHand.MAIN_HAND,
                    null, rotation(0.0f, 0.0f), false);
        }
        require(data.activeUseItemRotations() == 100);
        require(data.pendingUseItemContexts() == 64);
        require(data.skippedUseItemContexts(InteractionHand.MAIN_HAND) == 36);
        for (int i = 0; i < 36; i++) {
            data.confirmUseItem(InteractionHand.MAIN_HAND, true);
        }
        require(data.skippedUseItemContexts(InteractionHand.MAIN_HAND) == 0);
        UseItemRotationTracker.Decision decision = data.finishUseItemRotations(
                rotation(50.0f, 10.0f), null, false, false);
        require(decision != null && !decision.valid());
        require(decision.batch().rotations() == 100);
        require(decision.batch().fireworkRotations() == 0);
        data.confirmUseItem(InteractionHand.MAIN_HAND, true);
        require(decision.batch().fireworkRotations() == 1);
        data.resetPacketState();
        require(data.pendingUseItemContexts() == 0);
        require(data.skippedUseItemContexts(InteractionHand.MAIN_HAND) == 0);
    }

    private static void verifyMismatchDrainBound() {
        PlayerPacketData data = new PlayerPacketData();
        int scheduleRequests = 0;
        for (int i = 0; i < 71; i++) {
            PlayerPacketData.TrackedUseItemRotation tracked =
                    data.queueUseItemRotation(
                            i, i, 5L, InteractionHand.MAIN_HAND,
                            true, rotation(-1.0f, -1.0f), false);
            if (tracked.decision() != null
                    && data.queueUseItemRotationMismatch(
                    tracked.decision().batch(),
                    tracked.decision().tickYaw(),
                    tracked.decision().tickPitch())) {
                scheduleRequests++;
            }
        }
        UseItemRotationTracker.Decision tick = data.finishUseItemRotations(
                rotation(-1.0f, -1.0f), null, false, false);
        require(tick != null && !tick.valid());
        if (data.queueUseItemRotationMismatch(
                tick.batch(), tick.tickYaw(), tick.tickPitch())) {
            scheduleRequests++;
        }
        require(scheduleRequests == 1);
        require(data.pendingUseItemRotationMismatches() == 65);

        int rotations = 0;
        int fireworks = 0;
        for (PlayerPacketData.UseItemRotationMismatch mismatch
                : data.drainUseItemRotationMismatches()) {
            rotations += mismatch.rotationCount();
            fireworks += mismatch.fireworkRotationCount();
            data.completeUseItemRotationBatch(mismatch.batch());
        }
        require(rotations == 71);
        require(fireworks == 71);
        require(!data.finishUseItemRotationMismatchDrain());
    }

    private static void verifyDeferredFireworkResolution() {
        PlayerPacketData data = new PlayerPacketData();
        data.queueUseItemRotation(
                10.0f, 1.0f, 6L, InteractionHand.MAIN_HAND,
                null, rotation(0.0f, 0.0f), false);
        UseItemRotationTracker.Decision decision = data.finishUseItemRotations(
                rotation(20.0f, 2.0f), null, false, false);
        require(decision != null && !decision.valid());
        require(data.queueUseItemRotationMismatch(
                decision.batch(), decision.tickYaw(), decision.tickPitch()));
        PlayerPacketData.UseItemRotationMismatch mismatch =
                data.drainUseItemRotationMismatches().get(0);
        require(mismatch != null && mismatch.unresolvedRotationCount() == 1);
        data.deferUseItemRotationMismatch(mismatch);
        mismatch = data.drainUseItemRotationMismatches().get(0);
        require(mismatch != null && mismatch.attempts() == 1);
        data.confirmUseItem(InteractionHand.MAIN_HAND, true);
        require(mismatch.unresolvedRotationCount() == 0);
        require(mismatch.fireworkRotationCount() == 1);
        data.completeUseItemRotationBatch(mismatch.batch());
        require(!data.finishUseItemRotationMismatchDrain());
    }

    private static void verifyMismatchOverflowKeepsUnresolved() {
        PlayerPacketData data = new PlayerPacketData();
        int scheduleRequests = 0;
        for (int i = 0; i < 65; i++) {
            PlayerPacketData.TrackedUseItemRotation tracked =
                    data.queueUseItemRotation(
                            i, i, 7L, InteractionHand.MAIN_HAND,
                            i == 64 ? null : true,
                            rotation(-1.0f, -1.0f), false);
            if (tracked.decision() != null
                    && data.queueUseItemRotationMismatch(
                    tracked.decision().batch(),
                    tracked.decision().tickYaw(),
                    tracked.decision().tickPitch())) {
                scheduleRequests++;
            }
        }
        UseItemRotationTracker.Decision tick = data.finishUseItemRotations(
                rotation(-1.0f, -1.0f), null, false, false);
        require(tick != null && !tick.valid());
        if (data.queueUseItemRotationMismatch(
                tick.batch(), tick.tickYaw(), tick.tickPitch())) {
            scheduleRequests++;
        }
        require(scheduleRequests == 1);
        require(data.pendingUseItemRotationMismatches() == 65);

        int unresolved = 0;
        for (PlayerPacketData.UseItemRotationMismatch mismatch
                : data.drainUseItemRotationMismatches()) {
            if (mismatch.unresolvedRotationCount() > 0) {
                unresolved++;
            }
            data.completeUseItemRotationBatch(mismatch.batch());
        }
        require(unresolved == 1);
        require(!data.finishUseItemRotationMismatchDrain());
        data.resetPacketState();
    }

    private static UseItemRotationTracker.Decision tickDecision(
            float[] currentRotation,
            float[] previousRotation,
            boolean rotationTickPacket,
            boolean canSkipTicks) {
        PlayerPacketData data = new PlayerPacketData();
        data.queueUseItemRotation(
                10.0f, 1.0f, 1L, InteractionHand.MAIN_HAND,
                true, rotation(0.0f, 0.0f), canSkipTicks);
        return data.finishUseItemRotations(
                currentRotation, previousRotation,
                rotationTickPacket, canSkipTicks);
    }

    private static float[] rotation(float yaw, float pitch) {
        return new float[]{yaw, pitch};
    }

    private static void verifyTeleportCompensation() {
        PlayerPacketData absolute = new PlayerPacketData();
        absolute.queueTeleport(
                10.0, 64.0, -5.0, 90.0f, 20.0f,
                RelativeFlag.NONE, 0);
        require(absolute.checkTeleport(
                10.0, 64.0, -5.0, 90.0f, 20.0f));

        PlayerPacketData relative = new PlayerPacketData();
        relative.updatePosition(100.0, 50.0, 25.0);
        relative.queueTeleport(
                1.0, 2.0, 3.0, 0.0f, 0.0f,
                RelativeFlag.X.or(RelativeFlag.Y)
                        .or(RelativeFlag.Z)
                        .or(RelativeFlag.YAW)
                        .or(RelativeFlag.PITCH),
                0);
        require(relative.checkTeleport(
                101.0, 52.0, 28.0, 135.0f, -30.0f));
    }

    private static void verifyCameraCompensation() {
        PlayerPacketData data = new PlayerPacketData();
        PlayerPacketData.TrackedUseItemRotation tracked = data.queueUseItemRotation(
                45.0f, 10.0f, 12L, InteractionHand.MAIN_HAND,
                true, rotation(0.0f, 0.0f), false);
        data.queueCameraState(false, 0);
        data.applyTransactionState();
        require(!data.cameraEntitySelf.get());
        require(data.activeUseItemRotations() == 0);
        require(tracked.rotation().isComplete());
        data.resetPacketState();
        require(data.cameraEntitySelf.get());
        require(data.pendingUseItemContexts() == 0);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError();
        }
    }
}
