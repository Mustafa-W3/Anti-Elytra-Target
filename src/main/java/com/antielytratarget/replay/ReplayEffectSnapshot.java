package com.antielytratarget.replay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

public class ReplayEffectSnapshot {

    public enum Type {
        PARTICLE,
        SOUND
    }

    private final Type type;
    private final String worldName;
    private final double x, y, z;
    private final String name;
    private final int count;
    private final double offsetX, offsetY, offsetZ;
    private final double extra;
    private final float volume;
    private final float pitch;

    public ReplayEffectSnapshot(Type type, String worldName,
                                double x, double y, double z,
                                String name, int count,
                                double offsetX, double offsetY, double offsetZ, double extra,
                                float volume, float pitch) {
        this.type = type;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name;
        this.count = count;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.extra = extra;
        this.volume = volume;
        this.pitch = pitch;
    }

    public static ReplayEffectSnapshot particle(String worldName,
                                                double x, double y, double z,
                                                String particle, int count,
                                                double offsetX, double offsetY, double offsetZ,
                                                double extra) {
        return new ReplayEffectSnapshot(Type.PARTICLE, worldName, x, y, z, particle, count,
                offsetX, offsetY, offsetZ, extra, 0.0f, 0.0f);
    }

    public static ReplayEffectSnapshot sound(String worldName,
                                             double x, double y, double z,
                                             String sound, float volume, float pitch) {
        return new ReplayEffectSnapshot(Type.SOUND, worldName, x, y, z, sound, 0,
                0.0, 0.0, 0.0, 0.0, volume, pitch);
    }

    public Type getType() { return type; }
    public String getWorldName() { return worldName; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public String getName() { return name; }
    public int getCount() { return count; }
    public double getOffsetX() { return offsetX; }
    public double getOffsetY() { return offsetY; }
    public double getOffsetZ() { return offsetZ; }
    public double getExtra() { return extra; }
    public float getVolume() { return volume; }
    public float getPitch() { return pitch; }

    public String toToken() {
        String raw = String.join("\t",
                type.name(),
                s(worldName),
                fmt(x), fmt(y), fmt(z),
                s(name),
                Integer.toString(count),
                fmt(offsetX), fmt(offsetY), fmt(offsetZ), fmt(extra),
                Float.toString(volume), Float.toString(pitch)
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ReplayEffectSnapshot fromToken(String token) {
        try {
            String padded = token + "=".repeat((4 - token.length() % 4) % 4);
            String raw = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            String[] p = raw.split("\t", -1);
            if (p.length < 13) return null;
            return new ReplayEffectSnapshot(
                    Type.valueOf(p[0]),
                    p[1],
                    Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]),
                    p[5],
                    Integer.parseInt(p[6]),
                    Double.parseDouble(p[7]), Double.parseDouble(p[8]), Double.parseDouble(p[9]),
                    Double.parseDouble(p[10]),
                    Float.parseFloat(p[11]), Float.parseFloat(p[12])
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String s(String value) {
        return value != null ? value : "";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
