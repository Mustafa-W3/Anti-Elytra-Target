package com.antielytratarget.netty;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.CheckManager;
import com.antielytratarget.check.misc.OffhandFireworkSwitchCheck;
import com.antielytratarget.check.misc.PacketOrderECheck;
import com.antielytratarget.check.misc.IncapableSwapCheck;
import com.antielytratarget.check.misc.UseItemRotationMismatchCheck;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.SchedulerUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPickItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientWindowConfirmation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCursorItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

public class GrimPacketEventsListener extends PacketListenerAbstract {

    private final AntiElytraTargetPlugin plugin;

    public GrimPacketEventsListener(AntiElytraTargetPlugin plugin) {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) return;

        Player player = getPlayer(event);
        if (player == null) return;

        AETPlayer aet = plugin.getPlayerTracker().get(player);
        if (aet.checkManager == null) {
            plugin.initPlayerChecks(player);
        }

        PlayerPacketData data = plugin.getNettyManager().getData(player.getUniqueId());
        PacketTypeCommon packetType = event.getPacketType();

        handleTransaction(event, data);
        data.applyTransactionState();

        WrapperPlayClientPlayerFlying flying = null;
        boolean teleport = false;
        boolean duplicate = false;
        double movementDistanceSquared = Double.POSITIVE_INFINITY;
        if (WrapperPlayClientPlayerFlying.isFlying(packetType)) {
            flying = new WrapperPlayClientPlayerFlying(event);
            Location location = flying.getLocation();
            movementDistanceSquared = movementDistanceSquared(location, data.lastPosition.get());
            teleport = location != null
                    && flying.hasPositionChanged()
                    && flying.hasRotationChanged()
                    && data.checkTeleport(
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch());
            duplicate = isDuplicatePacket(
                    event.getClientVersion(), aet.isInsideVehicle(), data,
                    flying, teleport, movementDistanceSquared);
        }

        boolean tickPacket = isTickPacket(
                packetType, event.getClientVersion(), data, teleport, duplicate);
        long packetEventsTick = updatePacketEventsTick(data, tickPacket);
        if (tickPacket) {
            data.tickReliability.onTickBoundary(
                    event.getClientVersion(),
                    canSkipTicks(event.getClientVersion()),
                    flying != null,
                    flying != null && flying.hasPositionChanged() && !duplicate,
                    movementDistanceSquared,
                    aet.isInsideVehicle(),
                    plugin.getNettyManager().getServerTick(),
                    System.nanoTime());
        }

        if (!data.cameraEntitySelf.get()) {
            data.clearUseItemRotations();
        }

        if (flying != null) {
            if (!teleport) {
                data.didSendMovementBeforeTickEnd.set(true);
            }
            handleMovement(
                    player, data, flying, packetEventsTick,
                    tickPacket, duplicate, event.getClientVersion());
        } else if (tickPacket) {
            checkUseItemRotations(
                    player, data, false, event.getClientVersion());
        }

        handleItemAndActionState(event, player, aet, data, packetEventsTick);

        data.packetOrderProcessor.onPacketReceive(
                event, aet.isInsideVehicle(), data, tickPacket);

        CheckManager checkManager = aet.checkManager;

        PacketOrderECheck packetOrderE = checkManager.getCheck(PacketOrderECheck.class);
        if (packetOrderE != null) {
            packetOrderE.onPacketReceive(event);
            if (tickPacket) {
                packetOrderE.onClientTickBoundary(data);
            }
        }

        if (packetType == PacketType.Play.Client.CLIENT_TICK_END) {
            data.didSendMovementBeforeTickEnd.set(false);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) return;

        Player player = getPlayer(event);
        if (player == null || plugin.getNettyManager() == null) return;

        PlayerPacketData data = plugin.getNettyManager().getData(player.getUniqueId());
        PacketTypeCommon packetType = event.getPacketType();

        handleServerInventoryState(event, data);

        if (packetType == PacketType.Play.Server.CAMERA) {
            int transaction = beginStateTransaction(event, data);
            boolean self = new WrapperPlayServerCamera(event).getCameraId()
                    == player.getEntityId();
            data.queueCameraState(self, transaction);
            return;
        }

        if (packetType == PacketType.Play.Server.RESPAWN) {
            data.resetPacketState();
            scheduleInventorySeed(player, data);
            int transaction = beginStateTransaction(event, data);
            data.queueCameraState(true, transaction);
            return;
        }

        if (packetType == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            WrapperPlayServerPlayerPositionAndLook teleport =
                    new WrapperPlayServerPlayerPositionAndLook(event);
            int transaction = beginStateTransaction(event, data);
            data.queueTeleport(
                    teleport.getX(), teleport.getY(), teleport.getZ(),
                    teleport.getYaw(), teleport.getPitch(),
                    teleport.getRelativeFlags(), transaction);
        }
    }

    private void handleServerInventoryState(PacketSendEvent event,
                                            PlayerPacketData data) {
        PacketTypeCommon packetType = event.getPacketType();
        long tick = data.currentPacketEventsTick();

        if (packetType == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems items =
                    new WrapperPlayServerWindowItems(event);
            data.silentFireworkState.onServerWindowItems(
                    items.getWindowId(), items.getItems(), tick);
            return;
        }

        if (packetType == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot slot = new WrapperPlayServerSetSlot(event);
            data.silentFireworkState.onServerSetSlot(
                    slot.getWindowId(), slot.getSlot(), slot.getItem(), tick);
            return;
        }

        if (packetType == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            WrapperPlayServerSetPlayerInventory slot =
                    new WrapperPlayServerSetPlayerInventory(event);
            data.silentFireworkState.onServerPlayerInventorySlot(
                    slot.getSlot(), slot.getStack(), tick);
            return;
        }

        if (packetType == PacketType.Play.Server.SET_CURSOR_ITEM) {
            data.silentFireworkState.onServerCursor(
                    new WrapperPlayServerSetCursorItem(event).getStack(), tick);
            return;
        }

        if (packetType == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            data.silentFireworkState.onServerHeldItemChange(
                    new WrapperPlayServerHeldItemChange(event).getSlot(), tick);
            return;
        }

        if (packetType == PacketType.Play.Server.OPEN_WINDOW) {
            data.silentFireworkState.onOpenWindow(
                    new WrapperPlayServerOpenWindow(event).getContainerId());
            return;
        }

        if (packetType == PacketType.Play.Server.CLOSE_WINDOW) {
            data.silentFireworkState.onCloseWindow(
                    new WrapperPlayServerCloseWindow(event).getWindowId());
        }
    }

    private void scheduleInventorySeed(Player packetPlayer,
                                       PlayerPacketData data) {
        UUID uuid = packetPlayer.getUniqueId();
        SchedulerUtil.runForEntity(plugin, packetPlayer, () -> {
            Player current = plugin.getServer().getPlayer(uuid);
            if (current == packetPlayer && current.isOnline()
                    && plugin.getNettyManager().getData(uuid) == data) {
                data.silentFireworkState.seed(current);
            }
        }, null);
    }

    private int beginStateTransaction(PacketSendEvent event,
                                      PlayerPacketData data) {
        long serverTick = plugin.getNettyManager().getServerTick();
        data.transactionTracker.sendTransaction(event.getUser(), serverTick);
        int transaction = data.transactionTracker.getLastTransactionSent();
        event.getTasksAfterSend().add(() ->
                data.transactionTracker.sendTransaction(event.getUser(), serverTick));
        return transaction;
    }

    private long updatePacketEventsTick(PlayerPacketData data, boolean tickPacket) {
        if (tickPacket) {
            return data.beginPacketEventsTick();
        }

        return data.currentPacketEventsTick();
    }

    private void handleMovement(Player player,
                                PlayerPacketData data,
                                WrapperPlayClientPlayerFlying flying,
                                long packetEventsTick,
                                boolean tickPacket,
                                boolean duplicate,
                                ClientVersion clientVersion) {
        Location location = flying.getLocation();

        if (location != null && flying.hasPositionChanged() && !duplicate) {
            data.updatePosition(location.getX(), location.getY(), location.getZ());
            data.hasPositionUpdate.set(true);
        }

        if (location != null && flying.hasRotationChanged()) {
            data.updateRotation(location.getYaw(), location.getPitch());
            data.hasRotationUpdate.set(true);
        }

        data.updateOnGround(flying.isOnGround());
        data.movementTick.set(packetEventsTick);
        if (tickPacket) {
            checkUseItemRotations(
                    player, data, flying.hasRotationChanged(),
                    clientVersion);
        }
    }

    private void handleItemAndActionState(PacketReceiveEvent event,
                                          Player player,
                                          AETPlayer aet,
                                          PlayerPacketData data,
                                          long packetEventsTick) {
        PacketTypeCommon packetType = event.getPacketType();

        if (packetType == PacketType.Play.Client.USE_ITEM) {
            WrapperPlayClientUseItem useItem = new WrapperPlayClientUseItem(event);
            data.recordUseItem(packetEventsTick);
            SilentFireworkState.UseSnapshot useSnapshot =
                    data.silentFireworkState.onUseItem(
                            useItem.getHand(), packetEventsTick,
                            System.nanoTime());

            if (data.cameraEntitySelf.get() && supportsUseItemRotation(event)) {
                Boolean fireworkAtUse = useSnapshot.stateKnown()
                        ? useSnapshot.fireworkAtUse() : null;
                PlayerPacketData.TrackedUseItemRotation tracked =
                        data.queueUseItemRotation(
                                useItem.getYaw(), useItem.getPitch(),
                                packetEventsTick, useItem.getHand(),
                                fireworkAtUse, data.lastRotation.get(),
                                canUsePreviousRotation(event.getClientVersion()));
                handleUseItemRotationDecision(
                        player, data,
                        tracked.decision(), event.getClientVersion());
            }

            scheduleUseItemInventoryState(player, data, useItem.getHand(), true);
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            data.recordUseItem(packetEventsTick);
            scheduleUseItemInventoryState(player, data, null, false);
            return;
        }

        if (packetType == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int newSlot = new WrapperPlayClientHeldItemChange(event).getSlot();
            data.recordHeldSlot(newSlot, packetEventsTick);
            SilentFireworkState.SwapPattern pattern =
                    data.silentFireworkState.onHeldItemChange(
                            newSlot, packetEventsTick, System.nanoTime());
            dispatchIncapableSwapPattern(event, aet, data, pattern);
            return;
        }

        if (packetType == PacketType.Play.Client.CLICK_WINDOW) {
            SilentFireworkState.SwapPattern pattern =
                    data.silentFireworkState.onClickWindow(
                    new WrapperPlayClientClickWindow(event),
                    packetEventsTick, System.nanoTime());
            dispatchIncapableSwapPattern(event, aet, data, pattern);
            return;
        }

        if (packetType == PacketType.Play.Client.PICK_ITEM) {
            SilentFireworkState.SwapPattern pattern =
                    data.silentFireworkState.onPickItem(
                    new WrapperPlayClientPickItem(event).getSlot(),
                    packetEventsTick, System.nanoTime());
            dispatchIncapableSwapPattern(event, aet, data, pattern);
            return;
        }

        if (packetType == PacketType.Play.Client.CLOSE_WINDOW) {
            data.silentFireworkState.onCloseWindow(
                    new WrapperPlayClientCloseWindow(event).getWindowId());
            return;
        }

        if (packetType == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                data.recordOffhandSwap(packetEventsTick);
                SilentFireworkState.SwapPattern pattern =
                        data.silentFireworkState.onOffhandSwap(
                                packetEventsTick, System.nanoTime());
                dispatchIncapableSwapPattern(event, aet, data, pattern);
            }
            return;
        }

        if (packetType == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity interact =
                    new WrapperPlayClientInteractEntity(event);
            if (interact.getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                data.recordAttackAfterSlotChange(packetEventsTick);
                dispatchIncapableSwapAttack(
                        aet, packetEventsTick, interact.getEntityId());
            }
            return;
        }

        if (packetType == PacketType.Play.Client.ATTACK) {
            data.recordAttackAfterSlotChange(packetEventsTick);
            dispatchIncapableSwapAttack(aet, packetEventsTick,
                    new WrapperPlayClientAttack(event).getEntityId());
            return;
        }

        if (packetType == PacketType.Play.Client.SPECTATE_ENTITY) {
            data.recordAttackAfterSlotChange(packetEventsTick);
        }
    }

    private void dispatchIncapableSwapPattern(
            PacketReceiveEvent event, AETPlayer aet, PlayerPacketData data,
            SilentFireworkState.SwapPattern pattern) {
        if (pattern == null) return;
        IncapableSwapCheck check =
                aet.checkManager.getCheck(IncapableSwapCheck.class);
        ClientVersion clientVersion = event.getClientVersion();
        if (check == null || clientVersion.isOlderThan(ClientVersion.V_1_16_4)) {
            return;
        }

        boolean canSkip = canSkipTicks(clientVersion);
        boolean reliable = data.tickReliability.isTickingReliablyFor(
                3, canSkip, data.cameraEntitySelf.get());
        boolean normalConfidence = clientVersion
                .isNewerThanOrEquals(ClientVersion.V_1_21_2) && !canSkip;
        check.onSwapPattern(pattern, reliable, normalConfidence);
    }

    private void dispatchIncapableSwapAttack(AETPlayer aet, long tick,
                                              int targetEntityId) {
        IncapableSwapCheck check =
                aet.checkManager.getCheck(IncapableSwapCheck.class);
        if (check != null) {
            check.onAttackPacket(
                    tick, System.nanoTime(), targetEntityId);
        }
    }

    private void scheduleUseItemInventoryState(Player packetPlayer,
                                               PlayerPacketData data,
                                               InteractionHand hand,
                                               boolean mayBoost) {
        UUID uuid = packetPlayer.getUniqueId();
        SchedulerUtil.runForEntity(plugin, packetPlayer, () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != packetPlayer || !player.isOnline()) return;

            PlayerInventory inv = player.getInventory();
            boolean mainFirework = inv.getItemInMainHand().getType() == Material.FIREWORK_ROCKET;
            boolean offFirework = inv.getItemInOffHand().getType() == Material.FIREWORK_ROCKET;

            if (!mayBoost || !player.isGliding()) return;

            boolean usedFirework = hand == InteractionHand.OFF_HAND ? offFirework : mainFirework;
            if (!usedFirework) return;

            AETPlayer aet = plugin.getPlayerTracker().get(player);
            if (aet.checkManager == null) {
                plugin.initPlayerChecks(player);
            }

            aet.confirmRealBoost();
            aet.lastFwBoostTime = System.currentTimeMillis();

            OffhandFireworkSwitchCheck offhand =
                    aet.checkManager.getCheck(OffhandFireworkSwitchCheck.class);
            if (offhand != null && aet.ofwStep == 1) {
                offhand.onFireworkUse();
            }
        }, null);
    }

    private void checkUseItemRotations(Player player,
                                       PlayerPacketData data,
                                       boolean rotationTickPacket,
                                       ClientVersion clientVersion) {
        float[] currentRotation = data.lastRotation.get();
        UseItemRotationTracker.Decision decision = data.finishUseItemRotations(
                currentRotation, data.prevRotation.get(), rotationTickPacket,
                canUsePreviousRotation(clientVersion));
        handleUseItemRotationDecision(
                player, data, decision, clientVersion);
    }

    private void handleUseItemRotationDecision(
            Player player,
            PlayerPacketData data,
            UseItemRotationTracker.Decision decision,
            ClientVersion clientVersion) {
        if (decision == null) return;
        if (decision.valid()) {
            recordUseItemRotationMatch(player, decision.batch().tick());
            data.completeUseItemRotationBatch(decision.batch());
            return;
        }
        if (data.queueUseItemRotationMismatch(
                decision.batch(), decision.tickYaw(), decision.tickPitch())) {
            scheduleUseItemRotationMismatchDrain(player, data, clientVersion);
        }
    }

    private void recordUseItemRotationMatch(Player player, long packetTick) {
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        if (aet.checkManager == null) return;

        UseItemRotationMismatchCheck check =
                aet.checkManager.getCheck(UseItemRotationMismatchCheck.class);
        if (check != null) {
            check.onRotationMatch(packetTick);
        }
    }

    private void scheduleUseItemRotationMismatchDrain(
            Player player,
            PlayerPacketData data,
            ClientVersion clientVersion) {
        SchedulerUtil.runForEntityLater(
                plugin, player,
                () -> drainUseItemRotationMismatches(
                        player.getUniqueId(), data, clientVersion),
                data::clearUseItemRotationMismatches,
                1L);
    }

    private void drainUseItemRotationMismatches(
            UUID uuid,
            PlayerPacketData data,
            ClientVersion clientVersion) {
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()
                || plugin.getNettyManager().getExistingData(uuid) != data) {
            data.clearUseItemRotationMismatches();
            return;
        }

        try {
            for (PlayerPacketData.UseItemRotationMismatch mismatch
                    : data.drainUseItemRotationMismatches()) {
                if (mismatch.unresolvedRotationCount() > 0
                        && mismatch.attempts() < 2) {
                    data.deferUseItemRotationMismatch(mismatch);
                    continue;
                }
                try {
                    flagUseItemRotationMismatch(
                            player, data, mismatch, clientVersion);
                } catch (RuntimeException exception) {
                    plugin.debug("[UseItemRotationMismatch] Drain failed: "
                            + exception.getMessage());
                }
            }
        } finally {
            if (data.finishUseItemRotationMismatchDrain()) {
                scheduleUseItemRotationMismatchDrain(
                        player, data, clientVersion);
            }
        }
    }

    private void flagUseItemRotationMismatch(
            Player player,
            PlayerPacketData data,
            PlayerPacketData.UseItemRotationMismatch mismatch,
            ClientVersion clientVersion) {
        try {
            AETPlayer aet = plugin.getPlayerTracker().get(player);
            if (aet.checkManager == null) {
                plugin.initPlayerChecks(player);
            }

            UseItemRotationMismatchCheck check =
                    aet.checkManager.getCheck(UseItemRotationMismatchCheck.class);
            if (check == null) return;

            int rotations = mismatch.rotationCount();
            int fireworkRotations = mismatch.fireworkRotationCount();
            for (int i = 0; i < rotations; i++) {
                check.flagMismatch(
                        player,
                        clientVersion,
                        mismatch.useItemYaw(), mismatch.useItemPitch(),
                        mismatch.tickYaw(), mismatch.tickPitch(),
                        mismatch.packetTick(),
                        i < fireworkRotations);
            }
        } finally {
            data.completeUseItemRotationBatch(mismatch.batch());
        }
    }

    private void handleTransaction(PacketReceiveEvent event, PlayerPacketData data) {
        if (event.getPacketType() == PacketType.Play.Client.WINDOW_CONFIRMATION) {
            WrapperPlayClientWindowConfirmation transaction = new WrapperPlayClientWindowConfirmation(event);
            short id = transaction.getActionId();
            if (data.transactionTracker.onPong(id)) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PONG) {
            WrapperPlayClientPong pong = new WrapperPlayClientPong(event);
            int id = pong.getId();
            if (id == (short) id && data.transactionTracker.onPong((short) id)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean supportsUseItemRotation(PacketReceiveEvent event) {
        if (event.getClientVersion().isOlderThan(ClientVersion.V_1_21)) {
            return false;
        }

        try {
            return PacketEvents.getAPI().getServerManager().getVersion()
                    .isNewerThanOrEquals(ServerVersion.V_1_21);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean canSkipTicks(ClientVersion clientVersion, ServerVersion serverVersion) {
        return clientVersion.isNewerThanOrEquals(ClientVersion.V_1_9)
                && !(clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_2)
                && serverVersion.isNewerThanOrEquals(ServerVersion.V_1_21_2));
    }

    private boolean canSkipTicks(ClientVersion clientVersion) {
        return canSkipTicks(
                clientVersion,
                PacketEvents.getAPI().getServerManager().getVersion());
    }

    private boolean canUsePreviousRotation(ClientVersion clientVersion) {
        return canSkipTicks(clientVersion)
                && plugin.getConfigManager().getConfig().getBoolean(
                "checks.use_item_rotation_mismatch.allow_previous_rotation", true);
    }

    static boolean isTickPacket(boolean clientSupportsEndTick,
                                boolean didSendMovementBeforeTickEnd,
                                boolean tickEndPacket,
                                boolean flyingPacket,
                                boolean teleport,
                                boolean duplicate) {
        if (clientSupportsEndTick
                && !didSendMovementBeforeTickEnd
                && tickEndPacket) {
            return true;
        }

        return flyingPacket && !teleport && !duplicate;
    }

    private boolean isTickPacket(PacketTypeCommon packetType,
                                 ClientVersion clientVersion,
                                 PlayerPacketData data,
                                 boolean teleport,
                                 boolean duplicate) {
        return isTickPacket(
                clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21_2),
                data.didSendMovementBeforeTickEnd.get(),
                packetType == PacketType.Play.Client.CLIENT_TICK_END,
                WrapperPlayClientPlayerFlying.isFlying(packetType),
                teleport,
                duplicate);
    }

    static boolean isDuplicateMovement(ClientVersion clientVersion,
                                       boolean teleport,
                                       boolean hasPosition,
                                       boolean hasRotation,
                                       boolean inVehicle,
                                       boolean onGround,
                                       boolean previousOnGround,
                                       double distanceSquared) {
        if (teleport) return false;
        if (clientVersion.isOlderThan(ClientVersion.V_1_17)
                || clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21)) {
            return false;
        }
        if (!hasPosition || !hasRotation) return false;
        if (inVehicle) return true;
        return onGround == previousOnGround
                && distanceSquared < 0.0002 * 0.0002;
    }

    private boolean isDuplicatePacket(ClientVersion clientVersion,
                                       boolean insideVehicle,
                                       PlayerPacketData data,
                                       WrapperPlayClientPlayerFlying flying,
                                       boolean teleport,
                                       double distanceSquared) {
        return isDuplicateMovement(
                clientVersion,
                teleport,
                flying.hasPositionChanged(),
                flying.hasRotationChanged(),
                insideVehicle,
                flying.isOnGround(),
                data.lastOnGround.get(),
                distanceSquared);
    }

    private static double movementDistanceSquared(Location location, double[] previous) {
        if (location == null || previous == null) {
            return Double.POSITIVE_INFINITY;
        }

        double deltaX = location.getX() - previous[0];
        double deltaY = location.getY() - previous[1];
        double deltaZ = location.getZ() - previous[2];
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private Player getPlayer(PacketReceiveEvent event) {
        return getPlayer(
                event.getPlayer(),
                event.getUser() != null ? event.getUser().getUUID() : null);
    }

    private Player getPlayer(PacketSendEvent event) {
        return getPlayer(
                event.getPlayer(),
                event.getUser() != null ? event.getUser().getUUID() : null);
    }

    private Player getPlayer(Object raw, UUID uuid) {
        if (raw instanceof Player player) return player;
        return uuid != null ? Bukkit.getPlayer(uuid) : null;
    }

}
