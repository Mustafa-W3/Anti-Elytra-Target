package com.antielytratarget.netty;

import com.antielytratarget.check.misc.IncapableSwapCheck;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;

public final class SilentFireworkStateRegressionSimulation {

    private SilentFireworkStateRegressionSimulation() {
    }

    public static void main(String[] args) {
        installTestPacketEventsApi();
        verifySameTickHotbarPattern();
        verifyOffhandInventoryPath();
        verifyUseRequiresTrackedRocket();
        verifySingleSlotCorrectionDoesNotRestoreReliability();
        verifyDeniedInteractionCannotConfirmLaterBoost();
        verifyNonCandidateInteractionKeepsQueueAligned();
        verifyExactAttackEvidenceCorrelation();
    }

    private static void installTestPacketEventsApi() {
        ServerManager serverManager = () -> ServerVersion.V_1_21;
        PacketEvents.setAPI(new PacketEventsAPI<>() {
            private final com.github.retrooper.packetevents.netty.NettyManager nettyManager =
                    new io.github.retrooper.packetevents.netty.NettyManagerImpl();

            @Override public boolean isLoaded() { return true; }
            @Override public void init() { }
            @Override public boolean isInitialized() { return true; }
            @Override public boolean isTerminated() { return false; }
            @Override public Object getPlugin() { return null; }
            @Override public ServerManager getServerManager() { return serverManager; }
            @Override public ProtocolManager getProtocolManager() { return null; }
            @Override public PlayerManager getPlayerManager() { return null; }
            @Override public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() {
                return nettyManager;
            }
            @Override public ChannelInjector getInjector() { return null; }
        });
    }

    private static void verifySameTickHotbarPattern() {
        SilentFireworkState state = seededState(false);
        long tick = 582L;
        long start = 1_000_000_000L;

        require(state.onHeldItemChange(5, tick, start) == null,
                "switching to the rocket should arm, not complete, IncapableSwap");
        SilentFireworkState.UseSnapshot use = state.onUseItem(
                InteractionHand.MAIN_HAND, tick, start + 1_000_000L);
        require(use.stateKnown() && use.fireworkAtUse(),
                "USE_ITEM must resolve the tracked slot-5 rocket");

        long stagedSequenceId = state.onServerItemInteraction(
                true, true, true, start + 1_500_000L);
        long sequenceId = state.confirmServerElytraBoost(
                start + 1_600_000L);
        require(stagedSequenceId == sequenceId && sequenceId >= 0L,
                "an allowed interaction and real boost must confirm one sequence");
        SilentFireworkState.SwapPattern pattern =
                state.onHeldItemChange(0, tick, start + 2_000_000L);
        require(pattern != null && pattern.sameClientTick(),
                "slot 0 -> 5 -> use -> 0 in tick 582 must complete IncapableSwap");
        require(pattern.path() == SilentFireworkState.SwapPath.HOTBAR,
                "hotbar packets must retain their path");
        require(pattern.sequenceId() == sequenceId,
                "server firework confirmation must correlate by sequence id");
        require(pattern.originalSlot() == 0 && pattern.rocketSlot() == 5,
                "IncapableSwap must retain the original and rocket slots");
    }

    private static void verifyOffhandInventoryPath() {
        SilentFireworkState state = seededState(true);
        long tick = 600L;
        long start = 3_000_000_000L;

        require(state.onOffhandSwap(tick, start) == null,
                "weapon/offhand-rocket swap should arm the inventory path");
        SilentFireworkState.UseSnapshot use = state.onUseItem(
                InteractionHand.MAIN_HAND, tick, start + 1_000_000L);
        require(use.fireworkAtUse(),
                "offhand swap must place the tracked rocket in the main hand");
        SilentFireworkState.SwapPattern pattern =
                state.onOffhandSwap(tick, start + 2_000_000L);
        require(pattern != null
                        && pattern.path() == SilentFireworkState.SwapPath.OFFHAND,
                "restoring the weapon must complete the offhand path");
    }

    private static void verifyUseRequiresTrackedRocket() {
        SilentFireworkState state = seededState(false);
        long tick = 20L;
        state.onHeldItemChange(1, tick, 2_000_000_000L);
        SilentFireworkState.UseSnapshot use = state.onUseItem(
                InteractionHand.MAIN_HAND, tick, 2_001_000_000L);
        require(!use.fireworkAtUse(),
                "a non-rocket selected slot must never count as firework use");
        require(state.onHeldItemChange(0, tick, 2_002_000_000L) == null,
                "fast ordinary slot use/return must not produce IncapableSwap evidence");
    }

    private static void verifySingleSlotCorrectionDoesNotRestoreReliability() {
        SilentFireworkState state = seededState(false);
        long tick = 30L;

        state.onHeldItemChange(99, tick, 4_000_000_000L);
        state.onServerSetSlot(0, 36, item(ItemTypes.DIAMOND_SWORD), tick);

        require(state.onHeldItemChange(5, tick, 4_001_000_000L) == null,
                "one corrected slot must not re-arm an invalidated inventory copy");
        state.onUseItem(InteractionHand.MAIN_HAND, tick, 4_002_000_000L);
        require(state.onHeldItemChange(0, tick, 4_003_000_000L) == null,
                "partial server corrections must not create false swap evidence");
    }

    private static void verifyDeniedInteractionCannotConfirmLaterBoost() {
        SilentFireworkState state = seededState(true);
        long tick = 700L;
        long start = 5_000_000_000L;

        state.onHeldItemChange(5, tick, start);
        state.onUseItem(InteractionHand.MAIN_HAND, tick,
                start + 1_000_000L);
        require(state.onServerItemInteraction(
                        true, true, false, start + 2_000_000L) < 0L,
                "a DENY interaction must discard its packet candidate");
        require(state.confirmServerElytraBoost(start + 3_000_000L) < 0L,
                "a denied interaction must never confirm a later boost");
        SilentFireworkState.SwapPattern deniedPattern =
                state.onHeldItemChange(0, tick, start + 4_000_000L);
        require(deniedPattern != null,
                "the packet pattern remains observable even when server use is denied");

        state.onHeldItemChange(5, tick + 1, start + 10_000_000L);
        state.onUseItem(InteractionHand.MAIN_HAND, tick + 1,
                start + 11_000_000L);
        long accepted = state.onServerItemInteraction(
                true, true, true, start + 12_000_000L);
        long confirmed = state.confirmServerElytraBoost(
                start + 13_000_000L);
        SilentFireworkState.SwapPattern acceptedPattern =
                state.onHeldItemChange(0, tick + 1,
                        start + 14_000_000L);

        require(acceptedPattern != null && accepted == confirmed
                        && confirmed == acceptedPattern.sequenceId(),
                "the next real boost must confirm only its own packet sequence");
        require(confirmed != deniedPattern.sequenceId(),
                "a later boost must not revive a denied sequence");
    }

    private static void verifyNonCandidateInteractionKeepsQueueAligned() {
        SilentFireworkState state = seededState(true);
        long start = 6_000_000_000L;

        state.onUseItem(InteractionHand.MAIN_HAND, 800L, start);
        require(state.onServerItemInteraction(
                        true, false, true, start + 1_000_000L) < 0L,
                "ordinary main-hand interactions must consume their queue entry");

        state.onHeldItemChange(5, 801L, start + 2_000_000L);
        state.onUseItem(InteractionHand.MAIN_HAND, 801L,
                start + 3_000_000L);
        long staged = state.onServerItemInteraction(
                true, true, true, start + 4_000_000L);
        require(staged >= 0L && state.confirmServerElytraBoost(
                        start + 5_000_000L) == staged,
                "a preceding ordinary interaction must not shift boost correlation");
    }

    private static void verifyExactAttackEvidenceCorrelation() {
        require(isCompleteEvidence(41, 41, 41, 0.95F, 7.0),
                "matching boost, target, cooldown and damage must complete evidence");
        require(!isCompleteEvidence(41, 42, 41, 1.0F, 100.0),
                "a cooldown event from another target must never correlate");
        require(!isCompleteEvidence(41, 41, 42, 1.0F, 100.0),
                "damage on another target must never correlate");
        require(!isCompleteEvidence(41, 41, 41, 0.90F, 100.0),
                "Paper's original pre-reset strength must be strictly above 0.9");
        require(!isCompleteEvidence(41, 41, 41, 0.50F, 1000.0),
                "plugin-inflated raw damage must not replace cooldown proof");
        require(!isCompleteEvidence(41, 41, 41, 1.0F, 100.0, 0.0),
                "zero final damage must not count as a successful combat hit");
    }

    private static boolean isCompleteEvidence(
            int packetTarget, int cooldownTarget, int damageTarget,
            float cooldownStrength, double rawDamage) {
        return isCompleteEvidence(packetTarget, cooldownTarget, damageTarget,
                cooldownStrength, rawDamage, rawDamage);
    }

    private static boolean isCompleteEvidence(
            int packetTarget, int cooldownTarget, int damageTarget,
            float cooldownStrength, double rawDamage, double finalDamage) {
        IncapableSwapCheck check = new IncapableSwapCheck(null, null);
        long now = System.nanoTime();
        SilentFireworkState.SwapPattern pattern =
                new SilentFireworkState.SwapPattern(
                        1L, SilentFireworkState.SwapPath.HOTBAR,
                        0, 5, 900L, 900L, 900L,
                        now, now + 1_000_000L, now + 2_000_000L,
                        true, true, "minecraft:diamond_sword");

        check.onSwapPattern(pattern, true, true);
        check.onServerFireworkUse(1L, true, now + 2_500_000L);
        check.onAttackPacket(900L, now + 3_000_000L, packetTarget);
        check.onAttackCooldown(
                cooldownTarget, cooldownStrength, now + 3_500_000L);
        check.onSuccessfulAttack(
                damageTarget, "minecraft:diamond_sword", rawDamage, finalDamage,
                now + 4_000_000L);
        return firstTrackedPatternIsComplete(check);
    }

    private static boolean firstTrackedPatternIsComplete(
            IncapableSwapCheck check) {
        try {
            Field pendingField = IncapableSwapCheck.class
                    .getDeclaredField("pending");
            pendingField.setAccessible(true);
            ArrayDeque<?> pending =
                    (ArrayDeque<?>) pendingField.get(check);
            Object tracked = pending.peekFirst();
            if (tracked == null) return false;

            Method complete = tracked.getClass()
                    .getDeclaredMethod("isComplete");
            complete.setAccessible(true);
            return (boolean) complete.invoke(tracked);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "could not inspect IncapableSwap evidence", exception);
        }
    }

    private static SilentFireworkState seededState(boolean gliding) {
        ItemStack[] hotbar = new ItemStack[9];
        for (int i = 0; i < hotbar.length; i++) hotbar[i] = ItemStack.EMPTY;
        hotbar[0] = item(ItemTypes.DIAMOND_SWORD);
        hotbar[5] = item(ItemTypes.FIREWORK_ROCKET);

        SilentFireworkState state = new SilentFireworkState();
        state.seed(0, hotbar, item(ItemTypes.FIREWORK_ROCKET), gliding);
        return state;
    }

    private static ItemStack item(
            com.github.retrooper.packetevents.protocol.item.type.ItemType type) {
        return ItemStack.builder().type(type).amount(1).build();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
