package com.antielytratarget.netty;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TransactionTracker {

private static final int PKT_CLIENTBOUND_PING = 0x36;
    private static final int PKT_SERVERBOUND_PONG = 0x26;

private final AtomicInteger idCounter = new AtomicInteger(30001);

private final ConcurrentLinkedDeque<TransactionEntry> pending = new ConcurrentLinkedDeque<>();

private final AtomicLong lastConfirmedTick = new AtomicLong(-1);
    private final AtomicLong latencyNanos = new AtomicLong(0);
    private final AtomicInteger lastTransactionSent = new AtomicInteger(0);
    private final AtomicInteger lastTransactionReceived = new AtomicInteger(0);
    private volatile long lastPongTimeNanos = 0;

public void sendTransaction(Channel channel, long serverTick) {
        if (channel == null || !channel.isActive()) return;

        int id = nextId();
        addPending((short) id, serverTick);

channel.eventLoop().execute(() -> {
            if (!channel.isActive()) return;
            ByteBuf buf = Unpooled.buffer(5);
            writeVarInt(buf, PKT_CLIENTBOUND_PING);
            buf.writeInt(id);
            channel.writeAndFlush(buf);
        });
    }

    public void sendTransaction(User user, long serverTick) {
        if (user == null) return;

        int id = nextId();
        addPending((short) id, serverTick);

        try {
            ServerVersion version = PacketEvents.getAPI().getServerManager().getVersion();
            if (version.isNewerThanOrEquals(ServerVersion.V_1_17)) {
                user.sendPacketSilently(new WrapperPlayServerPing(id));
            } else {
                user.sendPacketSilently(
                        new WrapperPlayServerWindowConfirmation(
                                0, (short) id, false));
            }
        } catch (Throwable ignored) {
        }
    }

public boolean onPong(int pongId) {
        boolean pendingId = false;
        for (TransactionEntry entry : pending) {
            if (entry.id == (short) pongId) {
                pendingId = true;
                break;
            }
        }
        if (!pendingId) return false;

        boolean found = false;
        long now = System.nanoTime();

while (!pending.isEmpty()) {
            TransactionEntry entry = pending.peekFirst();
            if (entry == null) break;

            entry.confirm();
            lastConfirmedTick.set(entry.serverTick);
            latencyNanos.set(now - entry.sendTimeNanos);
            lastPongTimeNanos = now;
            lastTransactionReceived.incrementAndGet();

            pending.pollFirst();

            if (entry.id == (short) pongId) {
                found = true;
                break;
            }
        }

        return found;
    }

public boolean handleInbound(int packetId, ByteBuf buf) {
        if (packetId != PKT_SERVERBOUND_PONG) return false;
        if (buf.readableBytes() < 4) return false;

        int pongId = buf.readInt();
        return onPong(pongId);
    }

public long getLastConfirmedTick() {
        return lastConfirmedTick.get();
    }

public long getLatencyMs() {
        return latencyNanos.get() / 1_000_000L;
    }

public int getLatencyTicks() {
        long ms = getLatencyMs();
        return Math.max(1, (int) (ms / 50L));
    }

public long timeSinceLastPongMs() {
        if (lastPongTimeNanos == 0) return Long.MAX_VALUE;
        return (System.nanoTime() - lastPongTimeNanos) / 1_000_000L;
    }

public int pendingCount() {
        return pending.size();
    }

public boolean isCalibrated() {
        return lastConfirmedTick.get() >= 0;
    }

    public int getLastTransactionSent() {
        return lastTransactionSent.get();
    }

    public int getLastTransactionReceived() {
        return lastTransactionReceived.get();
    }

private int nextId() {
        int id = idCounter.decrementAndGet();

        if (id < 20000) {
            idCounter.set(30001);
            id = 30000;
        }
        return id;
    }

    private void addPending(short id, long serverTick) {
        pending.addLast(new TransactionEntry(id, serverTick, System.nanoTime()));
        lastTransactionSent.incrementAndGet();

        while (pending.size() > 100) {
            pending.pollFirst();
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
