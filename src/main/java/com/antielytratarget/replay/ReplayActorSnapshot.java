package com.antielytratarget.replay;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class ReplayActorSnapshot {

    private final UUID uuid;
    private final String name;
    private final String type;
    private final boolean playerLike;
    private final double x, y, z;
    private final float yaw, pitch;
    private final boolean sneaking;
    private final boolean sprinting;
    private final boolean gliding;
    private final double health;
    private final String mainHand;
    private final String offHand;
    private final String helmet;
    private final String chestplate;
    private final String leggings;
    private final String boots;
    private final ReplaySkinSnapshot skin;
    private final boolean swingArm;
    private final boolean damaged;

    public ReplayActorSnapshot(UUID uuid, String name, String type, boolean playerLike,
                               double x, double y, double z, float yaw, float pitch,
                               boolean sneaking, boolean sprinting, boolean gliding,
                               double health,
                               String mainHand, String offHand,
                               String helmet, String chestplate, String leggings, String boots,
                               ReplaySkinSnapshot skin, boolean swingArm, boolean damaged) {
        this.uuid = uuid;
        this.name = name;
        this.type = type;
        this.playerLike = playerLike;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.sneaking = sneaking;
        this.sprinting = sprinting;
        this.gliding = gliding;
        this.health = health;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.skin = skin != null ? skin : ReplaySkinSnapshot.empty();
        this.swingArm = swingArm;
        this.damaged = damaged;
    }

    public static ReplayActorSnapshot fromEntity(LivingEntity entity) {
        return fromEntity(entity, false, false);
    }

    public static ReplayActorSnapshot fromEntity(LivingEntity entity, boolean swingArm) {
        return fromEntity(entity, swingArm, false);
    }

    public static ReplayActorSnapshot fromEntity(LivingEntity entity, boolean swingArm, boolean damaged) {
        Location loc = entity.getLocation();
        EntityEquipment eq = entity.getEquipment();
        boolean isPlayer = entity instanceof Player;
        Player player = isPlayer ? (Player) entity : null;
        String actorName = isPlayer ? player.getName() : entity.getType().name();

        return new ReplayActorSnapshot(
                entity.getUniqueId(),
                actorName,
                entity.getType().name(),
                isPlayer,
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                entity.isSneaking(),
                isPlayer && ((Player) entity).isSprinting(),
                isPlayer && ((Player) entity).isGliding(),
                Math.max(0.0, entity.getHealth()),
                itemPayload(eq != null ? eq.getItemInMainHand() : null),
                itemPayload(eq != null ? eq.getItemInOffHand() : null),
                itemPayload(eq != null ? eq.getHelmet() : null),
                itemPayload(eq != null ? eq.getChestplate() : null),
                itemPayload(eq != null ? eq.getLeggings() : null),
                itemPayload(eq != null ? eq.getBoots() : null),
                isPlayer ? ReplaySkinSnapshot.fromPlayer(player) : ReplaySkinSnapshot.empty(),
                swingArm,
                damaged
        );
    }

    public String getKey() { return uuid.toString(); }
    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isPlayerLike() { return playerLike; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public boolean isSneaking() { return sneaking; }
    public boolean isSprinting() { return sprinting; }
    public boolean isGliding() { return gliding; }
    public double getHealth() { return health; }
    public String getMainHand() { return mainHand; }
    public String getOffHand() { return offHand; }
    public String getHelmet() { return helmet; }
    public String getChestplate() { return chestplate; }
    public String getLeggings() { return leggings; }
    public String getBoots() { return boots; }
    public ReplaySkinSnapshot getSkin() { return skin; }
    public boolean isSwingArm() { return swingArm; }
    public boolean isDamaged() { return damaged; }

    public String toToken() {
        String raw = String.join("\t",
                uuid.toString(), name, type, b(playerLike),
                Double.toString(x), Double.toString(y), Double.toString(z),
                Float.toString(yaw), Float.toString(pitch),
                b(sneaking), b(sprinting), b(gliding),
                Double.toString(health),
                s(mainHand), s(offHand), s(helmet), s(chestplate), s(leggings), s(boots),
                skin.toToken(), b(swingArm), b(damaged)
        );
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ReplayActorSnapshot fromToken(String token) {
        try {
            String padded = token + "=".repeat((4 - token.length() % 4) % 4);
            String raw = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            String[] p = raw.split("\t", -1);
            if (p.length < 19) return null;
            return new ReplayActorSnapshot(
                    UUID.fromString(p[0]), p[1], p[2], "1".equals(p[3]),
                    Double.parseDouble(p[4]), Double.parseDouble(p[5]), Double.parseDouble(p[6]),
                    Float.parseFloat(p[7]), Float.parseFloat(p[8]),
                    "1".equals(p[9]), "1".equals(p[10]), "1".equals(p[11]),
                    Double.parseDouble(p[12]),
                    p[13], p[14], p[15], p[16], p[17], p[18],
                    p.length > 19 ? ReplaySkinSnapshot.fromToken(p[19]) : ReplaySkinSnapshot.empty(),
                    p.length > 20 && "1".equals(p[20]),
                    p.length > 21 && "1".equals(p[21])
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String b(boolean value) {
        return value ? "1" : "0";
    }

    private static String s(String value) {
        return value != null ? value : "AIR";
    }

    private static String itemPayload(ItemStack item) {
        return ReplayItemCodec.encode(item);
    }
}
