package com.antielytratarget.prediction;

public class TickState {

    public final long serverTick;

public final double x, y, z;

public final double velX, velY, velZ;

public final float yaw, pitch;

public final boolean onGround;
    public final boolean gliding;

public final boolean fireworkBoosted;
    public final int fireworkPower;

public final boolean hasSlowFalling;
    public final boolean inWater;
    public final boolean inLava;
    public final boolean onClimbable;

    public TickState(long serverTick,
                     double x, double y, double z,
                     double velX, double velY, double velZ,
                     float yaw, float pitch,
                     boolean onGround, boolean gliding,
                     boolean fireworkBoosted, int fireworkPower,
                     boolean hasSlowFalling,
                     boolean inWater, boolean inLava, boolean onClimbable) {
        this.serverTick = serverTick;
        this.x = x;
        this.y = y;
        this.z = z;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.gliding = gliding;
        this.fireworkBoosted = fireworkBoosted;
        this.fireworkPower = fireworkPower;
        this.hasSlowFalling = hasSlowFalling;
        this.inWater = inWater;
        this.inLava = inLava;
        this.onClimbable = onClimbable;
    }

public double distanceTo(double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

public double speed() {
        return Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

public double horizontalSpeed() {
        return Math.sqrt(velX * velX + velZ * velZ);
    }

    @Override
    public String toString() {
        return String.format("TickState[t=%d pos=(%.2f,%.2f,%.2f) vel=(%.4f,%.4f,%.4f) yaw=%.1f pitch=%.1f g=%b gl=%b fw=%b]",
                serverTick, x, y, z, velX, velY, velZ, yaw, pitch, onGround, gliding, fireworkBoosted);
    }
}
