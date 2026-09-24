package com.antielytratarget.netty;

public class TransactionEntry {

    public final short id;
    public final long serverTick;
    public final long sendTimeNanos;
    private volatile boolean confirmed;
    private volatile long confirmTimeNanos;

    public TransactionEntry(short id, long serverTick, long sendTimeNanos) {
        this.id = id;
        this.serverTick = serverTick;
        this.sendTimeNanos = sendTimeNanos;
        this.confirmed = false;
    }

    public void confirm() {
        this.confirmed = true;
        this.confirmTimeNanos = System.nanoTime();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

public long getRttMs() {
        if (!confirmed) return -1;
        return (confirmTimeNanos - sendTimeNanos) / 1_000_000L;
    }

public long getRttNanos() {
        if (!confirmed) return -1;
        return confirmTimeNanos - sendTimeNanos;
    }
}
