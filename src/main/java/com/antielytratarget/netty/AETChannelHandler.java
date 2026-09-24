package com.antielytratarget.netty;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.misc.UseItemRotationMismatchCheck;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.SchedulerUtil;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public class AETChannelHandler extends ChannelDuplexHandler {

private static final int PKT_CLIENT_TICK_END  = 0x0A;
    private static final int PKT_INTERACT_ENTITY  = 0x0F;
    private static final int PKT_PLAYER_ACTION    = 0x1D;
    private static final int PKT_MOVE_POS_ROT     = 0x1A;
    private static final int PKT_MOVE_POS         = 0x1B;
    private static final int PKT_MOVE_ROT         = 0x1C;
    private static final int PKT_ON_GROUND        = 0x1E;
    private static final int PKT_PLAYER_INPUT     = 0x24;
    private static final int PKT_PLAYER_COMMAND   = 0x25;
    private static final int PKT_SET_HELD_ITEM    = 0x2C;
    private static final int PKT_USE_ITEM         = 0x37;
    private static final int PKT_USE_ITEM_ON      = 0x36;
    private static final int PKT_ANIMATION        = 0x38;
    private static final int PKT_PONG             = 0x26;

private static final int ACTION_SWAP_OFFHAND = 6;

    private final PlayerPacketData data;
    private final AntiElytraTargetPlugin plugin;
    private final Player packetPlayer;
    private final UUID playerUuid;
    private final ClientVersion clientVersion;
    private final ServerVersion serverVersion;
    private final boolean useItemRotationPacketsSupported;

private long tickCounter = 0;

private boolean slotChangedBeforeAttack = false;

    public AETChannelHandler(PlayerPacketData data,
                             AntiElytraTargetPlugin plugin,
                             Player packetPlayer,
                             ClientVersion clientVersion,
                             ServerVersion serverVersion) {
        this.data   = data;
        this.plugin = plugin;
        this.packetPlayer = packetPlayer;
        this.playerUuid = packetPlayer.getUniqueId();
        this.clientVersion = clientVersion;
        this.serverVersion = serverVersion;
        this.useItemRotationPacketsSupported = clientVersion != null
                && serverVersion != null
                && clientVersion.isNewerThanOrEquals(ClientVersion.V_1_21)
                && serverVersion.isNewerThanOrEquals(ServerVersion.V_1_21);
    }

@Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean consumed = false;
        if (!isPacketEventsPrimary() && msg instanceof ByteBuf buf) {
            buf.markReaderIndex();
            try {
                int packetId = readVarInt(buf);
                consumed = handleInbound(packetId, buf, ctx);
            } catch (Exception ignored) {

            } finally {
                buf.resetReaderIndex();
            }
        } else if (!isPacketEventsPrimary()) {
                handleInboundObject(msg);
        }

        if (consumed) {
            ReferenceCountUtil.release(msg);
            return;
        }

        super.channelRead(ctx, msg);
    }

@Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            var recorder = plugin.getReplayRecorder();
            if (recorder != null) {
                recorder.captureOutboundPacket(playerUuid, msg);
            }
        } catch (Throwable ignored) {
        }

        super.write(ctx, msg, promise);
    }

private boolean handleInbound(int id, ByteBuf buf, ChannelHandlerContext ctx) {
        switch (id) {

case PKT_PONG -> {
                return data.transactionTracker.handleInbound(id, buf);
            }

case PKT_CLIENT_TICK_END -> {
                tickCounter++;
                data.resetTick(tickCounter);
                slotChangedBeforeAttack = false;
                checkUseItemRotations(false);
            }

case PKT_MOVE_POS_ROT -> {
                tickCounter++;
                data.resetTick(tickCounter);
                slotChangedBeforeAttack = false;
                parsePositionRotation(buf);
                checkUseItemRotations(true);
            }
            case PKT_MOVE_POS -> {
                tickCounter++;
                data.resetTick(tickCounter);
                slotChangedBeforeAttack = false;
                parsePosition(buf);
                checkUseItemRotations(false);
            }
            case PKT_MOVE_ROT -> {
                tickCounter++;
                data.resetTick(tickCounter);
                slotChangedBeforeAttack = false;
                parseRotation(buf);
                checkUseItemRotations(true);
            }
            case PKT_ON_GROUND -> {
                tickCounter++;
                data.resetTick(tickCounter);
                slotChangedBeforeAttack = false;
                parseOnGround(buf);
                checkUseItemRotations(false);
            }

            case PKT_PLAYER_INPUT -> {
            }

            case PKT_PLAYER_COMMAND -> {
            }

case PKT_SET_HELD_ITEM -> {
                if (buf.readableBytes() >= 2) {
                    int slot = buf.readShort();
                    data.heldSlot.set(slot);
                    data.heldChangeTick.set(tickCounter);
                    if (data.useItemTick.get() == tickCounter) {
                        data.slotChangedAfterUse.set(true);
                    }
                    slotChangedBeforeAttack = true;
                }
            }

case PKT_USE_ITEM -> {
                data.useItemThisTick.set(true);
                data.useItemTick.set(tickCounter);
                ParsedUseItemRotation rotation = parseUseItemRotation(buf);
                if (rotation != null) {
                    trackUseItemRotation(rotation);
                }
            }

            case PKT_USE_ITEM_ON -> {
                data.useItemThisTick.set(true);
                data.useItemTick.set(tickCounter);
            }

case PKT_INTERACT_ENTITY -> {
                if (slotChangedBeforeAttack) {
                    data.attackedAfterSlotChange.set(true);
                }
            }

case PKT_PLAYER_ACTION -> {
                if (buf.readableBytes() >= 1) {
                    int action = readVarInt(buf);
                    if (action == ACTION_SWAP_OFFHAND) {
                        data.heldChangeTick.set(tickCounter);
                        if (data.useItemTick.get() == tickCounter) {
                            data.slotChangedAfterUse.set(true);
                        }
                        slotChangedBeforeAttack = true;
                    }
                }
            }

            case PKT_ANIMATION -> {
            }
        }
        return false;
    }

    private void handleInboundObject(Object packet) {
        if (packet == null) return;
        String name = packet.getClass().getName();
        String simple = packet.getClass().getSimpleName();

        if (isMovementObject(name, simple)) {
            tickCounter++;
            data.resetTick(tickCounter);
            slotChangedBeforeAttack = false;
            boolean hasRotation = parseMovementObject(packet, simple);
            checkUseItemRotations(hasRotation);
            return;
        }

        if (simple.contains("TickEnd") || simple.contains("ClientTickEnd")) {
            tickCounter++;
            data.resetTick(tickCounter);
            slotChangedBeforeAttack = false;
            checkUseItemRotations(false);
            return;
        }

        if (simple.contains("Pong")) {
            return;
        }

        if (simple.contains("PlayerInput") || name.contains("ServerboundPlayerInputPacket")) {
            return;
        }

        if (simple.contains("PlayerCommand") || simple.contains("EntityAction")) {
            return;
        }

        if (simple.contains("Swing") || simple.contains("Animation")) {
            return;
        }

        if (simple.contains("Abilities")) {
            return;
        }

        if (simple.contains("ClickContainer") || simple.contains("ContainerClick")
                || simple.contains("ClickWindow")) {
            return;
        }

        if (simple.contains("SetCarriedItem") || simple.contains("HeldItem")
                || simple.contains("SetHeldItem")) {
            Integer slot = readInt(packet, "getSlot", "getSlotId", "slot");
            if (slot != null) {
                data.heldSlot.set(slot);
                data.heldChangeTick.set(tickCounter);
                if (data.useItemTick.get() == tickCounter) {
                    data.slotChangedAfterUse.set(true);
                }
                slotChangedBeforeAttack = true;
            }
            return;
        }

        if (simple.contains("UseItemOn")) {
            data.useItemThisTick.set(true);
            data.useItemTick.set(tickCounter);
            return;
        }

        if (simple.contains("UseItem")) {
            data.useItemThisTick.set(true);
            data.useItemTick.set(tickCounter);
            ParsedUseItemRotation rotation = parseUseItemRotation(packet);
            if (rotation != null) {
                trackUseItemRotation(rotation);
            }
            return;
        }

        if (simple.contains("PlayerAction") || simple.contains("PlayerDigging")
                || simple.contains("PlayerActionPacket")) {
            return;
        }

        if (simple.contains("Interact") || simple.contains("UseEntity")) {
            if (slotChangedBeforeAttack) {
                data.attackedAfterSlotChange.set(true);
            }
        }
    }

    private boolean isMovementObject(String name, String simple) {
        if (simple.contains("MovePlayer") || simple.contains("Flying")) return true;
        if (name.contains("ServerboundMovePlayerPacket")) return true;
        return simple.contains("Position") || simple.contains("PosRot") || simple.contains("Rot");
    }

    private boolean parseMovementObject(Object packet, String simple) {
        double[] previousPosition = data.lastPosition.get();
        float[] previousRotation = data.lastRotation.get();

        boolean hasPosition = readBoolean(packet, "hasPosition", "hasPos")
                .orElse(simple.contains("Pos") || simple.contains("Position"));
        boolean hasRotation = readBoolean(packet, "hasRotation", "hasRot")
                .orElse(simple.contains("Rot") || simple.contains("Look"));

        if (hasPosition) {
            double fallbackX = previousPosition != null ? previousPosition[0] : 0.0;
            double fallbackY = previousPosition != null ? previousPosition[1] : 0.0;
            double fallbackZ = previousPosition != null ? previousPosition[2] : 0.0;
            double x = readDouble(packet, fallbackX, "getX", "x");
            double y = readDouble(packet, fallbackY, "getY", "y");
            double z = readDouble(packet, fallbackZ, "getZ", "z");
            data.updatePosition(x, y, z);
            data.hasPositionUpdate.set(true);
        }

        if (hasRotation) {
            float fallbackYaw = previousRotation != null ? previousRotation[0] : 0.0f;
            float fallbackPitch = previousRotation != null ? previousRotation[1] : 0.0f;
            float yaw = readFloat(packet, fallbackYaw, "getYRot", "getYaw", "yRot", "yaw");
            float pitch = readFloat(packet, fallbackPitch, "getXRot", "getPitch", "xRot", "pitch");
            data.updateRotation(yaw, pitch);
            data.hasRotationUpdate.set(true);
        }

        boolean onGround = readBoolean(packet, "isOnGround", "onGround").orElse(data.lastOnGround.get());
        data.updateOnGround(onGround);
        data.movementTick.set(tickCounter);
        return hasRotation;
    }

private void parsePositionRotation(ByteBuf buf) {
        if (buf.readableBytes() < 33) return;

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        byte flags = buf.readByte();
        boolean onGround = (flags & 0x01) != 0;

        data.updatePosition(x, y, z);
        data.updateRotation(yaw, pitch);
        data.updateOnGround(onGround);
        data.movementTick.set(tickCounter);
        data.hasPositionUpdate.set(true);
        data.hasRotationUpdate.set(true);
    }

private void parsePosition(ByteBuf buf) {
        if (buf.readableBytes() < 25) return;

        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        byte flags = buf.readByte();
        boolean onGround = (flags & 0x01) != 0;

        data.updatePosition(x, y, z);
        data.updateOnGround(onGround);
        data.movementTick.set(tickCounter);
        data.hasPositionUpdate.set(true);
    }

private void parseRotation(ByteBuf buf) {
        if (buf.readableBytes() < 9) return;

        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        byte flags = buf.readByte();
        boolean onGround = (flags & 0x01) != 0;

        data.updateRotation(yaw, pitch);
        data.updateOnGround(onGround);
        data.movementTick.set(tickCounter);
        data.hasRotationUpdate.set(true);
    }

private void parseOnGround(ByteBuf buf) {
        if (buf.readableBytes() < 1) return;

        byte flags = buf.readByte();
        boolean onGround = (flags & 0x01) != 0;

        data.updateOnGround(onGround);
        data.movementTick.set(tickCounter);
    }

private ParsedUseItemRotation parseUseItemRotation(ByteBuf buf) {
        if (!useItemRotationPacketsSupported) return null;

        int start = buf.readerIndex();
        try {
            int handId = readVarInt(buf);
            readVarInt(buf);
            if (buf.readableBytes() < 8) return null;
            InteractionHand hand = handId == 1
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            return new ParsedUseItemRotation(
                    buf.readFloat(), buf.readFloat(), hand);
        } catch (Exception ignored) {
            return null;
        } finally {
            buf.readerIndex(start);
        }
    }

    private ParsedUseItemRotation parseUseItemRotation(Object packet) {
        if (!useItemRotationPacketsSupported || packet == null) return null;
        Float yaw = readFloatBoxed(packet, "getYRot", "getYaw", "yRot", "yaw");
        Float pitch = readFloatBoxed(packet, "getXRot", "getPitch", "xRot", "pitch");
        if (yaw == null || pitch == null) return null;
        Object rawHand = invokeNoArg(packet, "getHand");
        if (rawHand == null) {
            rawHand = readField(packet, "hand");
        }
        InteractionHand hand = String.valueOf(rawHand).contains("OFF")
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        return new ParsedUseItemRotation(yaw, pitch, hand);
    }

    private Optional<Boolean> readBoolean(Object packet, String... names) {
        for (String name : names) {
            Object value = invokeNoArg(packet, name);
            if (value instanceof Boolean bool) return Optional.of(bool);
            value = readField(packet, name);
            if (value instanceof Boolean bool) return Optional.of(bool);
        }
        Boolean byType = readFieldByType(packet, Boolean.class, boolean.class, 0);
        return byType == null ? Optional.empty() : Optional.of(byType);
    }

    private Integer readInt(Object packet, String... names) {
        for (String name : names) {
            Object value = invokeNoArg(packet, name);
            if (value instanceof Number number) return number.intValue();
            value = readField(packet, name);
            if (value instanceof Number number) return number.intValue();
        }
        Number byType = readFieldByType(packet, Number.class, int.class, 0);
        return byType == null ? null : byType.intValue();
    }

    private double readDouble(Object packet, double fallback, String... names) {
        for (String name : names) {
            Object value = invokeWithFallback(packet, name, fallback);
            if (value instanceof Number number) return number.doubleValue();
            value = invokeNoArg(packet, name);
            if (value instanceof Number number) return number.doubleValue();
            value = readField(packet, name);
            if (value instanceof Number number) return number.doubleValue();
        }
        Number byType = readFieldByType(packet, Number.class, double.class, doubleFieldIndex(names));
        return byType == null ? fallback : byType.doubleValue();
    }

    private float readFloat(Object packet, float fallback, String... names) {
        Float value = readFloatBoxed(packet, names);
        return value == null ? fallback : value;
    }

    private Float readFloatBoxed(Object packet, String... names) {
        for (String name : names) {
            Object value = invokeWithFallback(packet, name, 0.0f);
            if (value instanceof Number number) return number.floatValue();
            value = invokeNoArg(packet, name);
            if (value instanceof Number number) return number.floatValue();
            value = readField(packet, name);
            if (value instanceof Number number) return number.floatValue();
        }
        int index = floatFieldIndex(names);
        Number byType = readFieldByType(packet, Number.class, float.class, index);
        return byType == null ? null : byType.floatValue();
    }

    private Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeWithFallback(Object target, String name, double fallback) {
        try {
            Method method = target.getClass().getMethod(name, double.class);
            return method.invoke(target, fallback);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeWithFallback(Object target, String name, float fallback) {
        try {
            Method method = target.getClass().getMethod(name, float.class);
            return method.invoke(target, fallback);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Exception ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T readFieldByType(Object target, Class<T> boxedType, Class<?> primitiveType, int index) {
        Class<?> type = target.getClass();
        int seen = 0;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (boxedType.isAssignableFrom(fieldType) || fieldType == primitiveType) {
                    if (seen++ == index) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(target);
                            return (T) value;
                        } catch (Exception ignored) {
                            return null;
                        }
                    }
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private int doubleFieldIndex(String[] names) {
        String joined = String.join("|", names).toLowerCase();
        if (joined.contains("y")) return 1;
        if (joined.contains("z")) return 2;
        return 0;
    }

    private int floatFieldIndex(String[] names) {
        String joined = String.join("|", names).toLowerCase();
        return joined.contains("pitch") || joined.contains("xrot") ? 1 : 0;
    }

    private void checkUseItemRotations(boolean rotationTickPacket) {
        if (!data.cameraEntitySelf.get()) {
            data.clearUseItemRotations();
            return;
        }

        UseItemRotationTracker.Decision decision = data.finishUseItemRotations(
                data.lastRotation.get(), data.prevRotation.get(),
                rotationTickPacket, canUsePreviousRotation());
        handleUseItemRotationDecision(decision);
    }

    private void trackUseItemRotation(ParsedUseItemRotation rotation) {
        SilentFireworkState.UseSnapshot useSnapshot =
                data.silentFireworkState.onUseItem(
                        rotation.hand(), tickCounter, System.nanoTime());
        Boolean fireworkAtUse = useSnapshot.stateKnown()
                ? useSnapshot.fireworkAtUse() : null;
        PlayerPacketData.TrackedUseItemRotation tracked =
                data.queueUseItemRotation(
                        rotation.yaw(), rotation.pitch(), tickCounter,
                        rotation.hand(), fireworkAtUse,
                        data.lastRotation.get(), canUsePreviousRotation());
        handleUseItemRotationDecision(tracked.decision());
    }

    private boolean canUsePreviousRotation() {
        return clientVersion != null && serverVersion != null
                && GrimPacketEventsListener.canSkipTicks(clientVersion, serverVersion)
                && plugin.getConfigManager().getConfig().getBoolean(
                "checks.use_item_rotation_mismatch.allow_previous_rotation", true);
    }

    private void handleUseItemRotationDecision(
            UseItemRotationTracker.Decision decision) {
        if (decision == null) return;
        if (decision.valid()) {
            recordUseItemRotationMatch(decision.batch().tick());
            data.completeUseItemRotationBatch(decision.batch());
            return;
        }
        if (data.queueUseItemRotationMismatch(
                decision.batch(), decision.tickYaw(), decision.tickPitch())) {
            scheduleUseItemRotationMismatchDrain();
        }
    }

    private void recordUseItemRotationMatch(long packetTick) {
        AETPlayer aet = plugin.getPlayerTracker().get(packetPlayer);
        if (aet.checkManager == null) return;

        UseItemRotationMismatchCheck check =
                aet.checkManager.getCheck(UseItemRotationMismatchCheck.class);
        if (check != null) {
            check.onRotationMatch(packetTick);
        }
    }

    private void scheduleUseItemRotationMismatchDrain() {
        SchedulerUtil.runForEntityLater(
                plugin, packetPlayer,
                this::drainUseItemRotationMismatches,
                data::clearUseItemRotationMismatches,
                1L);
    }

    private void drainUseItemRotationMismatches() {
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null || !player.isOnline() || player != packetPlayer
                || plugin.getNettyManager().getExistingData(playerUuid) != data) {
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
                    flagUseItemRotationMismatch(player, mismatch);
                } catch (RuntimeException exception) {
                    plugin.debug("[UseItemRotationMismatch] Drain failed: "
                            + exception.getMessage());
                }
            }
        } finally {
            if (data.finishUseItemRotationMismatchDrain()) {
                scheduleUseItemRotationMismatchDrain();
            }
        }
    }

    private void flagUseItemRotationMismatch(
            Player player,
            PlayerPacketData.UseItemRotationMismatch mismatch) {
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

    private static Channel getPlayerChannel(org.bukkit.entity.Player player) {
        try {
            Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Object conn = craftPlayer.getClass().getField("connection").get(craftPlayer);
            for (var f : conn.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(conn);
                if (val instanceof Channel ch) return ch;
                if (val != null && val.getClass().getSimpleName().contains("Connection")) {
                    for (var f2 : val.getClass().getDeclaredFields()) {
                        f2.setAccessible(true);
                        Object v2 = f2.get(val);
                        if (v2 instanceof Channel ch) return ch;
                    }
                    for (var f2 : val.getClass().getSuperclass().getDeclaredFields()) {
                        f2.setAccessible(true);
                        Object v2 = f2.get(val);
                        if (v2 instanceof Channel ch) return ch;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

private static int readVarInt(ByteBuf buf) {
        int value = 0, position = 0;
        byte current;
        do {
            if (!buf.isReadable()) throw new RuntimeException("Buffer exhausted reading VarInt");
            current = buf.readByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new RuntimeException("VarInt too large");
        } while (true);
        return value;
    }

public long getTickCounter() {
        return tickCounter;
    }

    private boolean isPacketEventsPrimary() {
        return plugin.getPacketEventsManager() != null && plugin.getPacketEventsManager().isStarted();
    }

    private record ParsedUseItemRotation(float yaw, float pitch,
                                         InteractionHand hand) {
    }

@Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        plugin.debug("[AET-Netty] Exception in channel handler: " + cause.getMessage());
        ctx.fireExceptionCaught(cause);
    }

}
