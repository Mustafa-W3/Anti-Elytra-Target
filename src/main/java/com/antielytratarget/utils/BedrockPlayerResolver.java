package com.antielytratarget.utils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class BedrockPlayerResolver {

    static final long FAST_RETRY_WINDOW_NANOS = 5_000_000_000L;
    static final long FAST_RETRY_INTERVAL_NANOS = 100_000_000L;
    static final long SLOW_RETRY_INTERVAL_NANOS = 10_000_000_000L;

    private final UUID uuid;
    private final Predicate<UUID> lookup;
    private final long createdNanos;
    private final AtomicLong nextLookupNanos;
    private volatile boolean confirmedBedrock;

    public BedrockPlayerResolver(UUID uuid) {
        this(uuid, BedrockPlayerUtil::isBedrockPlayer, System.nanoTime());
    }

    BedrockPlayerResolver(UUID uuid, Predicate<UUID> lookup, long createdNanos) {
        this.uuid = uuid;
        this.lookup = lookup;
        this.createdNanos = createdNanos;
        this.confirmedBedrock = false;
        this.nextLookupNanos = new AtomicLong(createdNanos);
    }

    public boolean isBedrockPlayer() {
        return resolve(System.nanoTime());
    }

    boolean resolve(long nowNanos) {
        if (confirmedBedrock) return true;

        long scheduled = nextLookupNanos.get();
        if (nowNanos < scheduled) return false;

        long age = Math.max(0L, nowNanos - createdNanos);
        long retryInterval = age <= FAST_RETRY_WINDOW_NANOS
                ? FAST_RETRY_INTERVAL_NANOS
                : SLOW_RETRY_INTERVAL_NANOS;
        long next = saturatingAdd(nowNanos, retryInterval);
        if (!nextLookupNanos.compareAndSet(scheduled, next)) {
            return confirmedBedrock;
        }

        if (lookup.test(uuid)) {
            confirmedBedrock = true;
            nextLookupNanos.set(Long.MAX_VALUE);
        }
        return confirmedBedrock;
    }

    private static long saturatingAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }
}
