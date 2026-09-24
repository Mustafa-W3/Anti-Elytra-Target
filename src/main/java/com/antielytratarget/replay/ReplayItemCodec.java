package com.antielytratarget.replay;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Base64;

final class ReplayItemCodec {

    private static final String PREFIX = "ITEM:";
    private static final int MAX_CACHE_ENTRIES = 4096;

    private static final Map<ItemFingerprint, EncodedItem> ENCODE_CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ItemFingerprint, EncodedItem> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    private static final Map<String, ItemStack> DECODE_CACHE =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            };

    private ReplayItemCodec() {
    }

    static String encode(ItemStack item) {
        if (item == null || item.getType().isAir()) return "AIR";

        if (isSimpleVisualItem(item)) {
            return item.getType().name();
        }

        ItemFingerprint fingerprint = ItemFingerprint.of(item);
        synchronized (ENCODE_CACHE) {
            EncodedItem cached = ENCODE_CACHE.get(fingerprint);
            if (cached != null && cached.matches(item)) {
                return cached.payload();
            }
        }

        String payload = encodeUncached(item);
        synchronized (ENCODE_CACHE) {
            ENCODE_CACHE.put(fingerprint, new EncodedItem(item.clone(), payload));
        }
        return payload;
    }

    private static String encodeUncached(ItemStack item) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
                output.writeObject(item);
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (Exception ignored) {
            return item.getType().name();
        }
    }

    static ItemStack decode(String value) {
        String normalized = normalize(value);
        synchronized (DECODE_CACHE) {
            ItemStack cached = DECODE_CACHE.get(normalized);
            if (cached != null) {
                return cached.clone();
            }
        }

        ItemStack decoded = decodeUncached(normalized);
        synchronized (DECODE_CACHE) {
            DECODE_CACHE.put(normalized, decoded.clone());
        }
        return decoded;
    }

    private static ItemStack decodeUncached(String value) {
        if (value.equals("AIR")) {
            return new ItemStack(Material.AIR);
        }

        if (value.startsWith(PREFIX)) {
            try {
                String payload = value.substring(PREFIX.length());
                byte[] bytes = Base64.getUrlDecoder().decode(pad(payload));
                try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
                    Object item = input.readObject();
                    if (item instanceof ItemStack stack) {
                        return stack;
                    }
                }
            } catch (Exception ignored) {
                return new ItemStack(Material.AIR);
            }
        }

        Material material = Material.matchMaterial(value);
        return new ItemStack(material != null ? material : Material.AIR);
    }

    static String normalize(String value) {
        return value == null || value.isBlank() ? "AIR" : value;
    }

    static void clearCaches() {
        synchronized (ENCODE_CACHE) {
            ENCODE_CACHE.clear();
        }
        synchronized (DECODE_CACHE) {
            DECODE_CACHE.clear();
        }
    }

    private static boolean isSimpleVisualItem(ItemStack item) {

return !item.hasItemMeta();
    }

    private static String pad(String value) {
        return value + "=".repeat((4 - value.length() % 4) % 4);
    }

    private record ItemFingerprint(String material, int amount, int hash) {
        static ItemFingerprint of(ItemStack item) {
            return new ItemFingerprint(item.getType().name(), item.getAmount(), item.hashCode());
        }
    }

    private record EncodedItem(ItemStack item, String payload) {
        boolean matches(ItemStack other) {
            return item != null && item.equals(other);
        }
    }
}
