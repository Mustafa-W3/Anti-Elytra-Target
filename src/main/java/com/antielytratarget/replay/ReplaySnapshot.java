package com.antielytratarget.replay;

import org.bukkit.Location;
import org.bukkit.Material;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReplaySnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

private final double x, y, z;
    private final float yaw, pitch;
    private final String worldName;

private final boolean sneaking;
    private final boolean sprinting;
    private final boolean gliding;
    private final boolean blocking;
    private final boolean swingArm;

private final String mainHand;
    private final String offHand;
    private final String helmet;
    private final String chestplate;
    private final String leggings;
    private final String boots;
    private final ReplaySkinSnapshot skin;

private final long tickOffset;

private final double velocityX, velocityY, velocityZ;
    private final double eyeHeight;
    private final boolean onGround;
    private final boolean packetBacked;
    private final long serverTick;
    private final long movementTick;
    private final List<ReplayActorSnapshot> actors;
    private final List<ReplayEffectSnapshot> effects;
    private final int fireworkPower;
    private final boolean fireworkStart;

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset) {
        this(x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                tickOffset, 0.0, 0.0, 0.0, false, false, -1L, -1L,
                Collections.emptyList(), ReplaySkinSnapshot.empty(), Collections.emptyList());
    }

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset,
                          double velocityX, double velocityY, double velocityZ,
                          boolean onGround, boolean packetBacked,
                          long serverTick, long movementTick) {
        this(x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                tickOffset, velocityX, velocityY, velocityZ,
                onGround, packetBacked, serverTick, movementTick,
                Collections.emptyList(), ReplaySkinSnapshot.empty(), Collections.emptyList());
    }

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset,
                          double velocityX, double velocityY, double velocityZ,
                          boolean onGround, boolean packetBacked,
                          long serverTick, long movementTick,
                          List<ReplayActorSnapshot> actors) {
        this(x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                tickOffset, velocityX, velocityY, velocityZ,
                onGround, packetBacked, serverTick, movementTick,
                actors, ReplaySkinSnapshot.empty(), Collections.emptyList());
    }

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset,
                          double velocityX, double velocityY, double velocityZ,
                          boolean onGround, boolean packetBacked,
                          long serverTick, long movementTick,
                          List<ReplayActorSnapshot> actors,
                          ReplaySkinSnapshot skin,
                          List<ReplayEffectSnapshot> effects) {
        this(x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                tickOffset, velocityX, velocityY, velocityZ,
                onGround, packetBacked, serverTick, movementTick,
                actors, skin, effects,
                defaultEyeHeight(gliding, sneaking));
    }

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset,
                          double velocityX, double velocityY, double velocityZ,
                          boolean onGround, boolean packetBacked,
                          long serverTick, long movementTick,
                          List<ReplayActorSnapshot> actors,
                          ReplaySkinSnapshot skin,
                          List<ReplayEffectSnapshot> effects,
                          double eyeHeight) {
        this(x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                tickOffset, velocityX, velocityY, velocityZ,
                onGround, packetBacked, serverTick, movementTick,
                actors, skin, effects, eyeHeight, 0, false);
    }

    public ReplaySnapshot(double x, double y, double z, float yaw, float pitch, String worldName,
                          boolean sneaking, boolean sprinting, boolean gliding, boolean blocking,
                          boolean swingArm,
                          String mainHand, String offHand,
                          String helmet, String chestplate, String leggings, String boots,
                          long tickOffset,
                          double velocityX, double velocityY, double velocityZ,
                          boolean onGround, boolean packetBacked,
                          long serverTick, long movementTick,
                          List<ReplayActorSnapshot> actors,
                          ReplaySkinSnapshot skin,
                          List<ReplayEffectSnapshot> effects,
                          double eyeHeight,
                          int fireworkPower,
                          boolean fireworkStart) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.worldName = worldName;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.gliding = gliding;
        this.blocking = blocking;
        this.swingArm = swingArm;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.skin = skin != null ? skin : ReplaySkinSnapshot.empty();
        this.tickOffset = tickOffset;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.eyeHeight = eyeHeight > 0.0 ? eyeHeight : defaultEyeHeight(gliding, sneaking);
        this.onGround = onGround;
        this.packetBacked = packetBacked;
        this.serverTick = serverTick;
        this.movementTick = movementTick;
        this.actors = actors == null || actors.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(actors));
        this.effects = effects == null || effects.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(effects));
        this.fireworkPower = Math.max(0, fireworkPower);
        this.fireworkStart = fireworkStart;
    }

public double getX()          { return x; }
    public double getY()          { return y; }
    public double getZ()          { return z; }
    public float  getYaw()        { return yaw; }
    public float  getPitch()      { return pitch; }
    public String getWorldName()  { return worldName; }

    public boolean isSneaking()   { return sneaking; }
    public boolean isSprinting()  { return sprinting; }
    public boolean isGliding()    { return gliding; }
    public boolean isBlocking()   { return blocking; }
    public boolean isSwingArm()   { return swingArm; }

    public String getMainHand()   { return mainHand; }
    public String getOffHand()    { return offHand; }
    public String getHelmet()     { return helmet; }
    public String getChestplate() { return chestplate; }
    public String getLeggings()   { return leggings; }
    public String getBoots()      { return boots; }
    public ReplaySkinSnapshot getSkin() { return skin; }

    public long getTickOffset()   { return tickOffset; }
    public double getVelocityX()  { return velocityX; }
    public double getVelocityY()  { return velocityY; }
    public double getVelocityZ()  { return velocityZ; }
    public double getEyeHeight()  { return eyeHeight; }
    public boolean isOnGround()   { return onGround; }
    public boolean isPacketBacked(){ return packetBacked; }
    public long getServerTick()   { return serverTick; }
    public long getMovementTick() { return movementTick; }
    public List<ReplayActorSnapshot> getActors() { return actors; }
    public List<ReplayEffectSnapshot> getEffects() { return effects; }
    public int getFireworkPower() { return fireworkPower; }
    public boolean isFireworkStart() { return fireworkStart; }

    public ReplaySnapshot withTickOffset(long newTickOffset) {
        return new ReplaySnapshot(
                x, y, z, yaw, pitch, worldName,
                sneaking, sprinting, gliding, blocking, swingArm,
                mainHand, offHand, helmet, chestplate, leggings, boots,
                newTickOffset,
                velocityX, velocityY, velocityZ,
                onGround, packetBacked, serverTick, movementTick,
                actors,
                skin,
                effects,
                eyeHeight,
                fireworkPower,
                fireworkStart
        );
    }

public String toLine() {
        return String.join(";",
                String.valueOf(tickOffset),
                fmt(x, 4), fmt(y, 4), fmt(z, 4),
                fmt(yaw, 2), fmt(pitch, 2),
                worldName,
                b(sneaking), b(sprinting), b(gliding), b(blocking), b(swingArm),
                s(mainHand), s(offHand), s(helmet), s(chestplate), s(leggings), s(boots),
                fmt(velocityX, 4), fmt(velocityY, 4), fmt(velocityZ, 4),
                b(onGround), b(packetBacked),
                String.valueOf(serverTick), String.valueOf(movementTick),
                encodeActors(actors),
                skin.toToken(),
                encodeEffects(effects),
                fmt(eyeHeight, 4),
                String.valueOf(fireworkPower),
                b(fireworkStart)
        );
    }

public static ReplaySnapshot fromLine(String line) {
        if (line == null || line.isBlank()) return null;
        String[] p = line.split(";", -1);
        if (p.length < 18) return null;
        try {
            return new ReplaySnapshot(
                    Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]),
                    Float.parseFloat(p[5]),
                    p[6],
                    "1".equals(p[7]),
                    "1".equals(p[8]),
                    "1".equals(p[9]),
                    "1".equals(p[10]),
                    "1".equals(p[11]),
                    p[12], p[13], p[14], p[15], p[16], p[17],
                    Long.parseLong(p[0]),
                    p.length > 18 ? Double.parseDouble(p[18]) : 0.0,
                    p.length > 19 ? Double.parseDouble(p[19]) : 0.0,
                    p.length > 20 ? Double.parseDouble(p[20]) : 0.0,
                    p.length > 21 && "1".equals(p[21]),
                    p.length > 22 && "1".equals(p[22]),
                    p.length > 23 ? Long.parseLong(p[23]) : -1L,
                    p.length > 24 ? Long.parseLong(p[24]) : -1L,
                    p.length > 25 ? decodeActors(p[25]) : Collections.emptyList(),
                    p.length > 26 ? ReplaySkinSnapshot.fromToken(p[26]) : ReplaySkinSnapshot.empty(),
                    p.length > 27 ? decodeEffects(p[27]) : Collections.emptyList(),
                    p.length > 28 ? Double.parseDouble(p[28])
                            : defaultEyeHeight("1".equals(p[9]), "1".equals(p[7])),
                    p.length > 29 ? Integer.parseInt(p[29]) : 0,
                    p.length > 30 && "1".equals(p[30])
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static double defaultEyeHeight(boolean gliding, boolean sneaking) {
        if (gliding) return 0.4;
        if (sneaking) return 1.27;
        return 1.62;
    }

    private static String b(boolean v) { return v ? "1" : "0"; }
    private static String s(String v)  { return v != null ? v : "AIR"; }
    private static String fmt(double v, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", v);
    }

    private static String encodeActors(List<ReplayActorSnapshot> actors) {
        if (actors == null || actors.isEmpty()) return "";
        List<String> tokens = new ArrayList<>(actors.size());
        for (ReplayActorSnapshot actor : actors) {
            tokens.add(actor.toToken());
        }
        return String.join("~", tokens);
    }

    private static List<ReplayActorSnapshot> decodeActors(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        List<ReplayActorSnapshot> actors = new ArrayList<>();
        for (String token : value.split("~")) {
            ReplayActorSnapshot actor = ReplayActorSnapshot.fromToken(token);
            if (actor != null) actors.add(actor);
        }
        return actors;
    }

    private static String encodeEffects(List<ReplayEffectSnapshot> effects) {
        if (effects == null || effects.isEmpty()) return "";
        List<String> tokens = new ArrayList<>(effects.size());
        for (ReplayEffectSnapshot effect : effects) {
            tokens.add(effect.toToken());
        }
        return String.join("~", tokens);
    }

    private static List<ReplayEffectSnapshot> decodeEffects(String value) {
        if (value == null || value.isBlank()) return Collections.emptyList();
        List<ReplayEffectSnapshot> effects = new ArrayList<>();
        for (String token : value.split("~")) {
            ReplayEffectSnapshot effect = ReplayEffectSnapshot.fromToken(token);
            if (effect != null) effects.add(effect);
        }
        return effects;
    }
}
