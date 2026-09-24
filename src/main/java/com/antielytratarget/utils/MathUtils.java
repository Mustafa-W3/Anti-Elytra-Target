package com.antielytratarget.utils;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;

public final class MathUtils {

    private MathUtils() {}

public static double angleBetween(Vector a, Vector b) {
        double aLenSq = a.lengthSquared();
        double bLenSq = b.lengthSquared();
        if (aLenSq == 0 || bLenSq == 0) return 180.0;

        double dot = (a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ())
                / Math.sqrt(aLenSq * bLenSq);
        dot = clamp(dot, -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

public static double getAngleToPlayer(Player attacker, Player target) {
        Location eyeLoc = attacker.getEyeLocation();
        Location targetEyeLoc = target.getEyeLocation();
        Vector lookDir = eyeLoc.getDirection();

        double dx = targetEyeLoc.getX() - eyeLoc.getX();
        double dy = targetEyeLoc.getY() - eyeLoc.getY();
        double dz = targetEyeLoc.getZ() - eyeLoc.getZ();

        double lookLenSq = lookDir.lengthSquared();
        double targetLenSq = dx * dx + dy * dy + dz * dz;
        if (lookLenSq == 0 || targetLenSq == 0) return 180.0;

        double dot = (lookDir.getX() * dx + lookDir.getY() * dy + lookDir.getZ() * dz)
                / Math.sqrt(lookLenSq * targetLenSq);
        return Math.toDegrees(Math.acos(clamp(dot, -1.0, 1.0)));
    }

public static double standardDeviation(double[] values) {
        if (values == null || values.length < 2) return Double.MAX_VALUE;
        double mean = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        double variance = 0;
        for (double v : values) variance += (v - mean) * (v - mean);
        return Math.sqrt(variance / values.length);
    }

    public static double average(Collection<? extends Number> values, double fallback) {
        if (values == null || values.isEmpty()) return fallback;

        double sum = 0.0;
        int count = 0;
        for (Number value : values) {
            sum += value.doubleValue();
            count++;
        }
        return count > 0 ? sum / count : fallback;
    }

    public static double standardDeviation(Collection<? extends Number> values) {
        if (values == null || values.size() < 2) return Double.MAX_VALUE;

        double mean = average(values, 0.0);
        double variance = 0.0;
        int count = 0;
        for (Number value : values) {
            double delta = value.doubleValue() - mean;
            variance += delta * delta;
            count++;
        }
        return count >= 2 ? Math.sqrt(variance / count) : Double.MAX_VALUE;
    }

public static float normalizeYaw(float yaw) {
        yaw %= 360;
        if (yaw >  180) yaw -= 360;
        if (yaw < -180) yaw += 360;
        return yaw;
    }

public static float yawDifference(float yaw1, float yaw2) {
        float diff = Math.abs(normalizeYaw(yaw1) - normalizeYaw(yaw2));
        if (diff > 180) diff = 360 - diff;
        return diff;
    }

public static double yawDeltaDegrees(float from, float to) {
        double diff = Math.abs((double) normalizeYaw(to) - (double) normalizeYaw(from));
        if (diff > 180) diff = 360 - diff;
        return diff;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

public static long gcd(long a, long b) {
        while (b != 0) { long t = b; b = a % b; a = t; }
        return Math.abs(a);
    }
}
