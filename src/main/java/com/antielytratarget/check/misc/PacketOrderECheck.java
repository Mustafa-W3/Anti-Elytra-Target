package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.check.PredictionCompleteCheck;
import com.antielytratarget.netty.GrimPacketOrderProcessor;
import com.antielytratarget.netty.PlayerPacketData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.ClientVersionExemptions;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;

@CheckData(name = "PacketOrderE", configName = "packet_order_e",
        decay = 0.05,
        description = "Changed held item slot during another conflicting action")
public class PacketOrderECheck extends AbstractCheck implements PredictionCompleteCheck {

    private static final int ATTACKING = 1 << 0;
    private static final int RIGHT_CLICKING = 1 << 1;
    private static final int OPENING_INVENTORY = 1 << 2;
    private static final int RELEASING = 1 << 3;
    private static final int SNEAKING = 1 << 4;
    private static final int SPRINTING = 1 << 5;
    private static final int LEAVING_BED = 1 << 6;
    private static final int GLIDING = 1 << 7;
    private static final int MOUNT_JUMPING = 1 << 8;

    private final ArrayDeque<FlagData> flags = new ArrayDeque<>();
    private final ArrayDeque<FlagData> queuedFlags = new ArrayDeque<>();

    public PacketOrderECheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    public synchronized void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || event.getPacketType() != PacketType.Play.Client.HELD_ITEM_CHANGE) {
            return;
        }
        PlayerPacketData data = plugin.getNettyManager().getData(aetPlayer.uuid);
        if (!data.cameraEntitySelf.get() || isPacketOrderBedrockExempt()
                || ClientVersionExemptions.isPacketOrderEExempt(event.getClientVersion())) {
            flags.clear();
            queuedFlags.clear();
            return;
        }
        GrimPacketOrderProcessor processor = data.packetOrderProcessor;
        int currentFlags = currentFlags(processor);
        if (currentFlags == 0 || !aetPlayer.isOrWasRecentlyGliding()
                || aetPlayer.isInsideVehicle()) {
            return;
        }
        int actionableFlags = ATTACKING | RIGHT_CLICKING | RELEASING;
        if ((currentFlags & actionableFlags) == 0) return;

        FlagData flagData = new FlagData(currentFlags);
        if (processor.canSkipTicks()) {
            addBounded(queuedFlags, flagData);
        } else {
            addBounded(flags, flagData);
        }
    }

    public synchronized void onClientTickBoundary(PlayerPacketData data) {
        if (queuedFlags.isEmpty()) return;

        boolean tickingReliably = data.tickReliability.isTickingReliablyFor(
                3,
                data.packetOrderProcessor.canSkipTicks(),
                data.cameraEntitySelf.get());
        if (tickingReliably) {
            for (FlagData dataPoint : queuedFlags) {
                addBounded(flags, dataPoint);
            }
        }
        queuedFlags.clear();
    }

    @Override
    public void onPredictionComplete() {
        ArrayDeque<FlagData> drained;

        synchronized (this) {
            if (flags.isEmpty()) {
                return;
            }

            drained = new ArrayDeque<>(flags);
            flags.clear();
        }

        Player player = aetPlayer.player;
        if (!canCheck(player) || isPacketOrderBedrockExempt()) return;

        for (FlagData dataPoint : drained) {
            int currentFlags = dataPoint.flags();
            if (plugin.isDebugEnabled()) {
                plugin.debug("[PacketOrderE] " + player.getName()
                        + " " + formatFlags(currentFlags));
            }
            flagAndAlert(player, null, 1.0);
        }
    }

    private boolean isPacketOrderBedrockExempt() {
        return aetPlayer.isBedrockPlayer() || isBedrockExempt();
    }

    private static void addBounded(ArrayDeque<FlagData> target, FlagData data) {
        target.addLast(data);
        while (target.size() > 10) {
            target.removeFirst();
        }
    }

    private int currentFlags(GrimPacketOrderProcessor processor) {
        int currentFlags = 0;
        if (processor.isAttackingOrStabbing()) currentFlags |= ATTACKING;
        if (processor.isRightClicking()) currentFlags |= RIGHT_CLICKING;
        if (processor.isOpeningInventory()) currentFlags |= OPENING_INVENTORY;
        if (processor.isReleasing()) currentFlags |= RELEASING;
        if (processor.isSneaking()) currentFlags |= SNEAKING;
        if (processor.isSprinting()) currentFlags |= SPRINTING;
        if (processor.isLeavingBed()) currentFlags |= LEAVING_BED;
        if (processor.isStartingToGlide()) currentFlags |= GLIDING;
        if (processor.isJumpingWithMount()) currentFlags |= MOUNT_JUMPING;
        return currentFlags;
    }

    private String formatFlags(int currentFlags) {
        return "attacking=" + has(currentFlags, ATTACKING)
                + ", rightClicking=" + has(currentFlags, RIGHT_CLICKING)
                + ", openingInventory=" + has(currentFlags, OPENING_INVENTORY)
                + ", releasing=" + has(currentFlags, RELEASING)
                + ", sneaking=" + has(currentFlags, SNEAKING)
                + ", sprinting=" + has(currentFlags, SPRINTING)
                + ", bed=" + has(currentFlags, LEAVING_BED)
                + ", gliding=" + has(currentFlags, GLIDING)
                + ", mountJumping=" + has(currentFlags, MOUNT_JUMPING);
    }

    private static boolean has(int currentFlags, int flag) {
        return (currentFlags & flag) != 0;
    }

    private record FlagData(int flags) {
    }
}
