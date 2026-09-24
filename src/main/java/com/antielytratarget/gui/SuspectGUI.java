package com.antielytratarget.gui;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.managers.ConfigManager;
import com.antielytratarget.managers.FlagManager;
import com.antielytratarget.managers.ProfileManager;
import com.antielytratarget.models.FlagLogEntry;
import com.antielytratarget.models.PlayerFlagData;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.stream.Collectors;

public class SuspectGUI implements Listener {

    private final Set<UUID> openGUIs = new HashSet<>();

    private final AntiElytraTargetPlugin plugin;
    private final FlagManager            flagManager;
    private final ProfileManager         profileManager;

    public SuspectGUI(AntiElytraTargetPlugin plugin) {
        this.plugin         = plugin;
        this.flagManager    = plugin.getFlagManager();
        this.profileManager = plugin.getProfileManager();
    }

public void open(Player viewer) {
        ConfigManager cfg  = plugin.getConfigManager();
        int           size = Math.max(9, Math.min(54, (cfg.getGuiSize() / 9) * 9));

        Inventory inv = Bukkit.createInventory(null, size,
                MessageUtil.colorize(cfg.getGuiTitle()));

        List<SuspectEntry> suspects = buildSuspectList();

        int slot = 0;
        for (SuspectEntry entry : suspects) {
            if (slot >= size) break;
            inv.setItem(slot, buildPlayerHead(entry));
            slot++;
        }

        openGUIs.add(viewer.getUniqueId());
        viewer.openInventory(inv);
    }

private List<SuspectEntry> buildSuspectList() {
        Map<UUID, SuspectEntry> merged = new LinkedHashMap<>();

for (Map.Entry<UUID, PlayerFlagData> e : flagManager.getAllFlagData().entrySet()) {
            UUID           uuid = e.getKey();
            PlayerFlagData data = e.getValue();
            int total  = data.getTotalFlags();
            int window = data.getFlagsInWindow();
            if (total == 0) continue;
            merged.put(uuid, new SuspectEntry(uuid, data.getPlayerName(), total, window));
        }

for (UUID uuid : profileManager.getAllTrackedUUIDs()) {
            List<FlagLogEntry> profile = profileManager.getProfile(uuid);
            if (profile.isEmpty()) continue;
            String playerName = profile.get(0).getPlayerName();
            int profileTotal  = profile.size();

            SuspectEntry existing = merged.get(uuid);
            if (existing == null) {
                merged.put(uuid, new SuspectEntry(uuid, playerName, profileTotal, 0));
            } else if (profileTotal > existing.totalFlags) {
                merged.put(uuid, new SuspectEntry(uuid, playerName, profileTotal, existing.windowFlags));
            }
        }

return merged.values().stream()
                .sorted((a, b) -> Integer.compare(b.totalFlags, a.totalFlags))
                .collect(Collectors.toList());
    }

@EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openGUIs.contains(player.getUniqueId())) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

String rawName;
        if (meta.hasDisplayName()) {
            Component displayComp = meta.displayName();
            if (displayComp != null) {
                rawName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(displayComp).trim();
            } else {
                return;
            }
        } else {
            return;
        }

        if (rawName.isEmpty()) return;

        player.closeInventory();
        openGUIs.remove(player.getUniqueId());
        sendProfileChat(player, rawName);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openGUIs.contains(player.getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        openGUIs.remove(player.getUniqueId());
    }

private void sendProfileChat(Player viewer, String targetName) {
        ConfigManager cfg = plugin.getConfigManager();

        Map.Entry<UUID, List<FlagLogEntry>> result = profileManager.findByName(targetName);

        if (result == null || result.getValue().isEmpty()) {
            MessageUtil.send(viewer,
                    cfg.getGuiNoProfile().replace("{player}", targetName));
            return;
        }

        List<FlagLogEntry> entries = result.getValue();
        int total = entries.size();
        String div = cfg.getGuiDivider();

        MessageUtil.send(viewer, div);
        MessageUtil.send(viewer,
                cfg.getGuiProfileHeader().replace("{player}", targetName));
        MessageUtil.send(viewer,
                cfg.getGuiProfileUuid().replace("{uuid}", result.getKey().toString()));
        MessageUtil.send(viewer,
                cfg.getGuiProfileTotal().replace("{total}", String.valueOf(total)));
        MessageUtil.send(viewer, div);

Map<String, Long> counts = entries.stream()
                .collect(Collectors.groupingBy(FlagLogEntry::getCheckName, Collectors.counting()));
        MessageUtil.send(viewer, cfg.getGuiProfileBreakdownHeader());
        counts.forEach((check, count) ->
                MessageUtil.send(viewer,
                        cfg.getGuiProfileBreakdownLine()
                                .replace("{check}", check)
                                .replace("{count}", String.valueOf(count))));

MessageUtil.send(viewer, div);
        MessageUtil.send(viewer, cfg.getGuiProfileRecentHeader());
        List<FlagLogEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        List<FlagLogEntry> recent = reversed.subList(0, Math.min(10, reversed.size()));
        for (int i = 0; i < recent.size(); i++) {
            MessageUtil.send(viewer,
                    cfg.getGuiProfileRecentLine()
                            .replace("{index}", String.valueOf(i + 1))
                            .replace("{entry}", recent.get(i).toDisplayLine()));
        }

        MessageUtil.send(viewer, div);
        MessageUtil.send(viewer,
                cfg.getGuiProfileFooter().replace("{player}", targetName));
    }

@SuppressWarnings("deprecation")
    private ItemStack buildPlayerHead(SuspectEntry entry) {
        ConfigManager cfg = plugin.getConfigManager();

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;

        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(entry.uuid);
        meta.setOwningPlayer(op);

int    blockT = cfg.getBlockModeThreshold();
        int    testT  = cfg.getTestModeThreshold();
        String stage  = entry.windowFlags >= blockT ? cfg.getGuiStageBlocking()
                      : entry.windowFlags >= testT  ? cfg.getGuiStageTesting()
                      :                               cfg.getGuiStageWatching();

meta.displayName(MessageUtil.colorize(
                cfg.getGuiHeadName().replace("{player}", entry.playerName)));

List<Component> lore = new ArrayList<>();
        lore.add(MessageUtil.colorize(
                cfg.getGuiLoreTotal().replace("{total}", String.valueOf(entry.totalFlags))));
        lore.add(MessageUtil.colorize(
                cfg.getGuiLoreWindow().replace("{window}", String.valueOf(entry.windowFlags))));
        lore.add(MessageUtil.colorize(
                cfg.getGuiLoreStatus().replace("{stage}", stage)));
        lore.add(Component.empty());
        lore.add(MessageUtil.colorize(cfg.getGuiLoreClick()));
        meta.lore(lore);

        head.setItemMeta(meta);
        return head;
    }

private static class SuspectEntry {
        final UUID   uuid;
        final String playerName;
        final int    totalFlags;
        final int    windowFlags;

        SuspectEntry(UUID uuid, String playerName, int totalFlags, int windowFlags) {
            this.uuid        = uuid;
            this.playerName  = playerName;
            this.totalFlags  = totalFlags;
            this.windowFlags = windowFlags;
        }
    }
}
