package com.antielytratarget.replay;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReplaySkinSnapshot {

    private static final ReplaySkinSnapshot EMPTY = new ReplaySkinSnapshot("", "");
    private static final long CACHE_MILLIS = 30_000L;
    private static final Map<UUID, CachedSkin> SKIN_CACHE = new ConcurrentHashMap<>();

    private final String value;
    private final String signature;

    public ReplaySkinSnapshot(String value, String signature) {
        this.value = value != null ? value : "";
        this.signature = signature != null ? signature : "";
    }

    public static ReplaySkinSnapshot empty() {
        return EMPTY;
    }

    static void clearCache() {
        SKIN_CACHE.clear();
    }

    public static ReplaySkinSnapshot fromPlayer(Player player) {
        if (player == null) return empty();

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        CachedSkin cached = SKIN_CACHE.get(uuid);
        if (cached != null && now - cached.cachedAt() <= CACHE_MILLIS) {
            return cached.skin();
        }

        ReplaySkinSnapshot skin = readFromPlayer(player);
        SKIN_CACHE.put(uuid, new CachedSkin(skin, now));
        return skin;
    }

    private static ReplaySkinSnapshot readFromPlayer(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Method getGameProfile = findMethod(handle.getClass(), "getGameProfile", 0);
            Object profile = getGameProfile.invoke(handle);
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            Object textureProperties = properties.getClass().getMethod("get", Object.class)
                    .invoke(properties, "textures");
            if (textureProperties instanceof Collection<?> collection && !collection.isEmpty()) {
                Object property = collection.iterator().next();
                String value = readPropertyString(property, "value", "getValue");
                String signature = readPropertyString(property, "signature", "getSignature");
                if (value != null && !value.isBlank()) {
                    return new ReplaySkinSnapshot(value, signature);
                }
            }
        } catch (Exception ignored) {
        }
        return empty();
    }

    private record CachedSkin(ReplaySkinSnapshot skin, long cachedAt) {
    }

    public String getValue() {
        return value;
    }

    public String getSignature() {
        return signature;
    }

    public boolean hasTexture() {
        return !value.isBlank();
    }

    public String key() {
        return value + "|" + signature;
    }

    public String toToken() {
        if (!hasTexture()) return "";
        String raw = value + "\t" + signature;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static ReplaySkinSnapshot fromToken(String token) {
        if (token == null || token.isBlank()) return empty();
        try {
            String padded = token + "=".repeat((4 - token.length() % 4) % 4);
            String raw = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\t", -1);
            return new ReplaySkinSnapshot(
                    parts.length > 0 ? parts[0] : "",
                    parts.length > 1 ? parts[1] : ""
            );
        } catch (Exception ignored) {
            return empty();
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Method method : current.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("method not found: " + name);
    }

    private static String readPropertyString(Object property, String modernName, String legacyName) {
        Object value = invokeNoArg(property, modernName);
        if (value == null) value = invokeNoArg(property, legacyName);
        if (value instanceof Optional<?> optional) {
            value = optional.orElse(null);
        }
        return value != null ? value.toString() : "";
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = findMethod(target.getClass(), name, 0);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
