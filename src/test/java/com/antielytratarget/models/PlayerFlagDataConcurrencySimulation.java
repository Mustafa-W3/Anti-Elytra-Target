package com.antielytratarget.models;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class PlayerFlagDataConcurrencySimulation {

    private PlayerFlagDataConcurrencySimulation() {
    }

    public static void main(String[] args) throws Exception {
        preservesWindowSemantics();
        doesNotLoseConcurrentFlags();
        cleanupCannotEraseConcurrentFlags();
        System.out.println("PlayerFlagData concurrency simulations passed.");
    }

    private static void preservesWindowSemantics() {
        PlayerFlagData data = new PlayerFlagData(
                java.util.UUID.randomUUID(), "TestPlayer");

        PlayerFlagData.FlagUpdate first = data.recordFlag(1_000L, 100L);
        assertUpdate(first, 1, 1, true);

        PlayerFlagData.FlagUpdate second = data.recordFlag(1_050L, 100L);
        assertUpdate(second, 2, 2, false);

        PlayerFlagData.FlagUpdate outOfOrder = data.recordFlag(1_040L, 100L);
        assertUpdate(outOfOrder, 3, 3, false);
        if (outOfOrder.lastFlagTime() != 1_050L) {
            throw new AssertionError("Last flag timestamp moved backwards");
        }

        if (data.resetWindowIfExpired(1_150L, 100L)) {
            throw new AssertionError("Window reset at the inclusive boundary");
        }
        if (!data.resetWindowIfExpired(1_151L, 100L)
                || data.getFlagsInWindow() != 0) {
            throw new AssertionError("Expired window was not reset");
        }
    }

    private static void doesNotLoseConcurrentFlags() throws Exception {
        PlayerFlagData data = new PlayerFlagData(
                java.util.UUID.randomUUID(), "ConcurrentPlayer");
        int writers = 8;
        int flagsPerWriter = 500;
        runConcurrently(writers, writer -> {
            for (int i = 0; i < flagsPerWriter; i++) {
                data.recordFlag(10_000L, 1_000L);
            }
        });

        int expected = writers * flagsPerWriter;
        if (data.getTotalFlags() != expected
                || data.getFlagsInWindow() != expected) {
            throw new AssertionError("Concurrent flags were lost: total="
                    + data.getTotalFlags() + ", window="
                    + data.getFlagsInWindow());
        }
    }

    private static void cleanupCannotEraseConcurrentFlags() throws Exception {
        PlayerFlagData data = new PlayerFlagData(
                java.util.UUID.randomUUID(), "CleanupRacePlayer");
        data.recordFlag(1_000L, 100L);

        int writers = 8;
        int flagsPerWriter = 250;
        runConcurrently(writers + 1, worker -> {
            if (worker == writers) {
                data.resetWindowIfExpired(1_200L, 100L);
                return;
            }
            for (int i = 0; i < flagsPerWriter; i++) {
                data.recordFlag(1_200L, 100L);
            }
        });

        int expectedWindow = writers * flagsPerWriter;
        if (data.getTotalFlags() != expectedWindow + 1
                || data.getFlagsInWindow() != expectedWindow) {
            throw new AssertionError("Cleanup erased concurrent flags: total="
                    + data.getTotalFlags() + ", window="
                    + data.getFlagsInWindow());
        }
    }

    private static void runConcurrently(
            int workers, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            for (int i = 0; i < workers; i++) {
                int worker = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    task.run(worker);
                    return null;
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Workers did not become ready");
            }
            start.countDown();
            for (Future<?> future : futures) future.get();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static void assertUpdate(
            PlayerFlagData.FlagUpdate update,
            int total, int window, boolean reset) {
        if (update.totalFlags() != total
                || update.flagsInWindow() != window
                || update.windowReset() != reset) {
            throw new AssertionError("Unexpected flag update: " + update);
        }
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int worker) throws Exception;
    }
}
