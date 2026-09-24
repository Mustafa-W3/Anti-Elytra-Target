package com.antielytratarget.netty;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;

public final class GrimTickReliability {

    private static final long MAX_RELIABLE_PACKET_GAP_NANOS = 90_000_000L;

    private int consecutiveReliableMovements;
    private long clientTick;
    private long lastMovementServerTick = Long.MIN_VALUE;
    private long lastMovementNanos = Long.MIN_VALUE;
    private long lastVehicleSwitchTick = Long.MIN_VALUE;
    private boolean vehicleStateKnown;
    private boolean inVehicle;

    public void onTickBoundary(ClientVersion clientVersion,
                               boolean canSkipTicks,
                               boolean movementPacket,
                               boolean hasPosition,
                               double distanceSquared,
                               boolean currentlyInVehicle,
                               long serverTick,
                               long receivedNanos) {
        clientTick++;

        if (!vehicleStateKnown) {
            vehicleStateKnown = true;
            inVehicle = currentlyInVehicle;
            lastVehicleSwitchTick = clientTick;
        } else if (inVehicle != currentlyInVehicle) {
            inVehicle = currentlyInVehicle;
            lastVehicleSwitchTick = clientTick;
            consecutiveReliableMovements = 0;
        }

        if (!canSkipTicks) {
            consecutiveReliableMovements = Integer.MAX_VALUE;
            rememberMovementBoundary(movementPacket, serverTick, receivedNanos);
            return;
        }

        if (!movementPacket) {
            consecutiveReliableMovements = 0;
            return;
        }

        double threshold = movementThreshold(clientVersion);
        boolean movedFarEnough = hasPosition
                && Double.isFinite(distanceSquared)
                && distanceSquared > threshold * threshold;
        boolean cadenceReliable = hasReliableCadence(serverTick, receivedNanos);

        if (movedFarEnough) {
            consecutiveReliableMovements = cadenceReliable
                    ? saturatingIncrement(consecutiveReliableMovements)
                    : 1;
        } else {
            consecutiveReliableMovements = 0;
        }

        rememberMovementBoundary(true, serverTick, receivedNanos);
    }

    public boolean isTickingReliablyFor(int ticks,
                                        boolean canSkipTicks,
                                        boolean cameraEntitySelf) {
        if (!cameraEntitySelf) return false;
        if (!canSkipTicks) return true;
        if (ticks <= 0) return true;

        boolean vehicleStable = !vehicleStateKnown
                || clientTick - lastVehicleSwitchTick > 1;
        return vehicleStable && consecutiveReliableMovements >= ticks;
    }

    public void reset() {
        consecutiveReliableMovements = 0;
        clientTick = 0;
        lastMovementServerTick = Long.MIN_VALUE;
        lastMovementNanos = Long.MIN_VALUE;
        lastVehicleSwitchTick = Long.MIN_VALUE;
        vehicleStateKnown = false;
        inVehicle = false;
    }

    int consecutiveReliableMovements() {
        return consecutiveReliableMovements;
    }

    static double movementThreshold(ClientVersion version) {
        return version.isOlderThan(ClientVersion.V_1_18_2) ? 0.03 : 0.0002;
    }

    private boolean hasReliableCadence(long serverTick, long receivedNanos) {
        if (lastMovementServerTick == Long.MIN_VALUE || lastMovementNanos == Long.MIN_VALUE) {
            return false;
        }

        long tickGap = serverTick - lastMovementServerTick;
        long timeGap = receivedNanos - lastMovementNanos;
        return tickGap >= 0 && tickGap <= 1
                && timeGap >= 0 && timeGap <= MAX_RELIABLE_PACKET_GAP_NANOS;
    }

    private void rememberMovementBoundary(boolean movementPacket,
                                          long serverTick,
                                          long receivedNanos) {
        if (!movementPacket) return;
        lastMovementServerTick = serverTick;
        lastMovementNanos = receivedNanos;
    }

    private static int saturatingIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }
}
