package com.antielytratarget.gui;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.managers.ConfigManager;
import com.antielytratarget.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LanguageGUI implements Listener {

    private static final int SIZE = 9;
    private static final int[] SLOTS = {0, 1, 2, 3, 4, 5, 6, 7};
    private static final Map<Integer, LanguageOption> OPTIONS_BY_SLOT = new HashMap<>();

    private final AntiElytraTargetPlugin plugin;
    private final Map<UUID, Inventory> openInventories = new HashMap<>();

    public LanguageGUI(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        for (int i = 0; i < LanguageOption.values().length; i++) {
            OPTIONS_BY_SLOT.put(SLOTS[i], LanguageOption.values()[i]);
        }
    }

    public void open(Player player) {
        ConfigManager config = plugin.getConfigManager();
        String title = config.msg("language_title", "<gold>Select Language");
        Inventory inventory = Bukkit.createInventory(null, SIZE, MessageUtil.colorize(title));

        for (int i = 0; i < LanguageOption.values().length; i++) {
            LanguageOption option = LanguageOption.values()[i];
            inventory.setItem(SLOTS[i], buildLanguageHead(option,
                    option.code.equals(config.getActiveLanguage())));
        }

        openInventories.put(player.getUniqueId(), inventory);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inventory = openInventories.get(player.getUniqueId());
        if (inventory == null || !event.getView().getTopInventory().equals(inventory)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) return;
        LanguageOption option = OPTIONS_BY_SLOT.get(event.getRawSlot());
        if (option == null || !player.isOp()) return;

        openInventories.remove(player.getUniqueId());
        player.closeInventory();
        if (!plugin.getConfigManager().setLanguage(option.code)) return;

        plugin.reloadLanguage();
        ConfigManager config = plugin.getConfigManager();
        MessageUtil.send(player, config.getPrefix()
                + config.msg("language_selected", "<green>Language changed to <yellow>{language}<green>.")
                .replace("{language}", option.nativeName));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inventory = openInventories.get(player.getUniqueId());
        if (inventory != null && event.getView().getTopInventory().equals(inventory)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openInventories.remove(player.getUniqueId());
        }
    }

    private ItemStack buildLanguageHead(LanguageOption option, boolean active) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) return item;

        if (option.playerName != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(option.playerName));
        } else {
            applyTexture(meta, option.textureHash);
        }

        meta.displayName(MessageUtil.colorize((active ? "<green>" : "<yellow>") + option.nativeName));
        List<Component> lore = new ArrayList<>();
        lore.add(MessageUtil.colorize("<gray>" + option.englishName));
        lore.add(Component.empty());
        lore.add(MessageUtil.colorize(plugin.getConfigManager().msg(
                active ? "language_active" : "language_click",
                active ? "<green>Currently active" : "<yellow>Click to select")));
        meta.lore(lore);
        if (active) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

private void applyTexture(SkullMeta meta, String textureHash) {
        String url = "https://textures.minecraft.net/texture/" + textureHash;
        try {
            Class<?> profileClass = Class.forName("org.bukkit.profile.PlayerProfile");
            Object profile = Bukkit.class.getMethod("createPlayerProfile", UUID.class, String.class)
                    .invoke(null, UUID.nameUUIDFromBytes(textureHash.getBytes(StandardCharsets.UTF_8)), null);
            Object textures = profileClass.getMethod("getTextures").invoke(profile);
            textures.getClass().getMethod("setSkin", URL.class).invoke(textures, new URL(url));
            Method setTextures = null;
            for (Method method : profileClass.getMethods()) {
                if (method.getName().equals("setTextures") && method.getParameterCount() == 1) {
                    setTextures = method;
                    break;
                }
            }
            if (setTextures == null) throw new NoSuchMethodException("PlayerProfile#setTextures");
            setTextures.invoke(profile, textures);
            meta.getClass().getMethod("setOwnerProfile", profileClass).invoke(meta, profile);
            return;
        } catch (Throwable ignored) {

        }

        try {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
            String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.nameUUIDFromBytes(textureHash.getBytes(StandardCharsets.UTF_8)), null);
            Object properties = gameProfileClass.getMethod("getProperties").invoke(profile);
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Constructor<?> propertyConstructor = propertyClass.getConstructor(String.class, String.class);
            Method put = properties.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(properties, "textures", propertyConstructor.newInstance("textures", value));

            Field profileField = null;
            Class<?> type = meta.getClass();
            while (type != null && profileField == null) {
                try { profileField = type.getDeclaredField("profile"); }
                catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
            }
            if (profileField != null) {
                profileField.setAccessible(true);
                profileField.set(meta, profile);
            }
        } catch (Throwable error) {
            plugin.debug("Could not apply language flag texture: " + error.getMessage());
        }
    }

    private enum LanguageOption {
        EN("en", "English", "English", null,
                "879d99d9c46474e2713a7e84a95e4ce7e8ff8ea4d164413a592e4435d2c6f9dc"),
        TR("tr", "Türkçe", "Turkish", null,
                "e6f18e2f98a5b706df2e5952ad68b82e49ad1aeb974ad1a5404516e2f4145ccb"),
        IT("it", "Italiano", "Italian", "PinguinoAz", null),
        ES("es", "Español", "Spanish", null,
                "32bd4521983309e0ad76c1ee29874287957ec3d96f8d889324da8c887e485ea8"),
        FR("fr", "Français", "French", "CCT6", null),
        DE("de", "Deutsch", "German", "Abseits", null),
        PT("pt", "Português", "Portuguese", null,
                "7a101cc663bbc04304edece68c68d3635bafee846df8e3d327f55f6b64f8fd35"),
        RU("ru", "Русский", "Russian", null,
                "16eafef980d6117dabe8982ac4b4509887e2c4621f6a8fe5c9b735a83d775ad");

        final String code;
        final String nativeName;
        final String englishName;
        final String playerName;
        final String textureHash;

        LanguageOption(String code, String nativeName, String englishName, String playerName, String textureHash) {
            this.code = code;
            this.nativeName = nativeName;
            this.englishName = englishName;
            this.playerName = playerName;
            this.textureHash = textureHash;
        }
    }
}
