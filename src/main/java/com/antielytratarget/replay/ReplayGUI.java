package com.antielytratarget.replay;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.utils.MessageUtil;
import com.antielytratarget.utils.PermissionUtil;
import com.antielytratarget.utils.SchedulerUtil;
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

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ReplayGUI implements Listener {

    private static final int GUI_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AntiElytraTargetPlugin plugin;

private final Map<UUID, Integer> openPages = new ConcurrentHashMap<>();

private final Map<UUID, List<File>> viewerReplayFiles = new ConcurrentHashMap<>();
    private final Map<UUID, String> openFilters = new ConcurrentHashMap<>();
    private final Map<ReplayFileKey, Optional<ReplayData.Summary>> summaryCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> viewerRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();

    public ReplayGUI(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

    public void shutdown() {
        openPages.clear();
        viewerReplayFiles.clear();
        openFilters.clear();
        summaryCache.clear();
        viewerRequests.clear();
    }

public void open(Player viewer) {
        openFilters.remove(viewer.getUniqueId());
        open(viewer, 1, null);
    }

    public void open(Player viewer, int page) {
        open(viewer, page, openFilters.get(viewer.getUniqueId()));
    }

    public void open(Player viewer, String playerName) {
        open(viewer, 1, playerName);
    }

    private void open(Player viewer, int page, String playerFilter) {
        open(viewer, page, playerFilter, beginRequest(viewer));
    }

    private void open(Player viewer, int page, String playerFilter, long requestId) {
        if (!isCurrentRequest(viewer, requestId)) return;
        ReplayRecorder recorder = plugin.getReplayRecorder();
        if (recorder == null) {
            MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                    + t("replay_not_enabled", "<red>Replay system is not enabled."));
            return;
        }

        File[] savedFiles = recorder.getSavedReplays();
        if (savedFiles.length == 0) {
            sendNoFiles(viewer, playerFilter);
            return;
        }

        Set<ReplayFileKey> currentKeys = new HashSet<>();
        List<File> missingSummaries = new ArrayList<>();
        Map<File, ReplayFileKey> requestedSummaryKeys = new HashMap<>();
        for (File file : savedFiles) {
            ReplayFileKey key = ReplayFileKey.of(file);
            currentKeys.add(key);
            if (!summaryCache.containsKey(key)) {
                missingSummaries.add(file);
                requestedSummaryKeys.put(file, key);
            }
        }
        summaryCache.keySet().retainAll(currentKeys);

        if (!missingSummaries.isEmpty()) {
            int requestedPage = page;
            recorder.loadReplaySummariesAsync(missingSummaries, summaries ->
                    SchedulerUtil.runForEntity(plugin, viewer, () -> {
                        if (!viewer.isOnline() || !isCurrentRequest(viewer, requestId)) return;
                        for (File file : missingSummaries) {
                            ReplayFileKey requestedKey = requestedSummaryKeys.get(file);
                            if (requestedKey != null && requestedKey.equals(ReplayFileKey.of(file))) {
                                summaryCache.put(requestedKey, Optional.ofNullable(summaries.get(file)));
                            }
                        }
                        open(viewer, requestedPage, playerFilter, requestId);
                    }, null));
            return;
        }

        File[] allFiles = filterFiles(savedFiles, playerFilter);
        if (allFiles.length == 0) {
            sendNoFiles(viewer, playerFilter);
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) allFiles.length / ITEMS_PER_PAGE));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        String targetTitle = playerFilter == null || playerFilter.isBlank()
                ? t("replay_gui_title", "Replays")
                : t("replay_gui_title_player", "Replays: {player}", "{player}", playerFilter);
        String title = plugin.getConfigManager().getPrefix() + "<yellow>" + targetTitle + " <gray>(" + page + "/" + totalPages + ")";
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, MessageUtil.colorize(title));

int startIdx = (page - 1) * ITEMS_PER_PAGE;
        int endIdx = Math.min(startIdx + ITEMS_PER_PAGE, allFiles.length);

        List<File> pageFiles = new ArrayList<>();
        for (int i = startIdx; i < endIdx; i++) {
            File file = allFiles[i];
            int slot = i - startIdx;
            inv.setItem(slot, buildReplayItem(file));
            pageFiles.add(file);
        }

if (page > 1) {
            inv.setItem(45, buildNavItem(Material.ARROW, t("replay_previous", "<yellow>← Previous Page"),
                    t("replay_go_page", "<gray>Click to go to page {page}", "{page}", String.valueOf(page - 1))));
        }

inv.setItem(49, buildNavItem(Material.BOOK, t("replay_system", "<gold>Replay System"),
                t("replay_total", "<gray>Total replays: <white>{total}", "{total}", String.valueOf(allFiles.length)),
                t("replay_page", "<gray>Page: <white>{page}/{pages}", "{page}", String.valueOf(page), "{pages}", String.valueOf(totalPages)),
                playerFilter == null || playerFilter.isBlank() ? "" : t("replay_player", "<gray>Player: <yellow>{player}", "{player}", playerFilter),
                "",
                t("replay_left_watch", "<dark_gray>Left-click a replay to watch"),
                t("replay_right_delete", "<dark_gray>Right-click to delete")));

if (page < totalPages) {
            inv.setItem(53, buildNavItem(Material.ARROW, t("replay_next", "<yellow>Next Page →"),
                    t("replay_go_page", "<gray>Click to go to page {page}", "{page}", String.valueOf(page + 1))));
        }

inv.setItem(51, buildNavItem(Material.BARRIER, t("replay_close", "<red>Close"), t("replay_close_lore", "<gray>Close this menu")));

        viewer.openInventory(inv);
        openPages.put(viewer.getUniqueId(), page);
        if (playerFilter == null || playerFilter.isBlank()) {
            openFilters.remove(viewer.getUniqueId());
        } else {
            openFilters.put(viewer.getUniqueId(), playerFilter);
        }
        viewerReplayFiles.put(viewer.getUniqueId(), pageFiles);
        viewerRequests.put(viewer.getUniqueId(), requestId);
    }

private ItemStack buildReplayItem(File file) {

        ReplayData.Summary summary = loadSummary(file);

        ItemStack item;
        if (summary != null) {
            item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skull = (SkullMeta) item.getItemMeta();
            if (skull != null) {

                try {
                    org.bukkit.OfflinePlayer offP = Bukkit.getOfflinePlayer(summary.getPlayerUUID());
                    skull.setOwningPlayer(offP);
                } catch (Exception ignored) {}

                skull.displayName(MessageUtil.colorize("<gold>" + summary.getPlayerName()));

                List<Component> lore = new ArrayList<>();
                lore.add(MessageUtil.colorize(t("replay_date", "<gray>Date: <white>{date}", "{date}", summary.getFormattedStartTime())));
                lore.add(MessageUtil.colorize(t("replay_duration", "<gray>Duration: <white>{seconds}s", "{seconds}", String.valueOf(summary.getDurationSeconds()))));
                lore.add(MessageUtil.colorize(t("replay_frames", "<gray>Frames: <white>{frames}", "{frames}", String.valueOf(summary.getSnapshotCount()))));
                lore.add(Component.empty());
                lore.add(MessageUtil.colorize(t("replay_left_watch_short", "<green>Left-click to watch")));
                lore.add(MessageUtil.colorize(t("replay_right_delete_short", "<red>Right-click to delete")));
                skull.lore(lore);

                item.setItemMeta(skull);
            }
        } else {

            item = new ItemStack(Material.GRAY_DYE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(MessageUtil.colorize(t("replay_corrupt", "<red>Corrupt Replay")));
                meta.lore(List.of(
                        MessageUtil.colorize(t("replay_file", "<gray>File: <white>{file}", "{file}", file.getName())),
                        MessageUtil.colorize(t("replay_could_not_load", "<red>Could not load this replay")),
                        Component.empty(),
                        MessageUtil.colorize(t("replay_right_delete_short", "<red>Right-click to delete"))
                ));
                item.setItemMeta(meta);
            }
        }

        return item;
    }

    private ItemStack buildNavItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtil.colorize(name));
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(MessageUtil.colorize(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

@EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openPages.containsKey(player.getUniqueId())) return;

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GUI_SIZE) return;

        int page = openPages.getOrDefault(player.getUniqueId(), 1);
        List<File> files = viewerReplayFiles.get(player.getUniqueId());

if (slot == 45) {

            String filter = openFilters.get(player.getUniqueId());
            player.closeInventory();
            open(player, page - 1, filter);
            return;
        }
        if (slot == 53) {

            String filter = openFilters.get(player.getUniqueId());
            player.closeInventory();
            open(player, page + 1, filter);
            return;
        }
        if (slot == 51) {

            player.closeInventory();
            return;
        }

if (slot < ITEMS_PER_PAGE && files != null && slot < files.size()) {
            File replayFile = files.get(slot);

            if (event.isRightClick()) {
                if (!PermissionUtil.has(player, PermissionUtil.REPLAY_DELETE)) {
                    MessageUtil.send(player, plugin.getConfigManager().getPrefix()
                            + plugin.getConfigManager().getMsgNoPermission());
                    return;
                }
                String filter = openFilters.get(player.getUniqueId());

                ReplayRecorder recorder = plugin.getReplayRecorder();
                if (recorder == null) {
                    player.closeInventory();
                    return;
                }
                boolean deleted = recorder.deleteReplay(replayFile);
                if (deleted) {
                    MessageUtil.send(player, plugin.getConfigManager().getPrefix()
                            + t("replay_deleted", "<green>Replay deleted: <yellow>{file}", "{file}", replayFile.getName()));
                } else {
                    MessageUtil.send(player, plugin.getConfigManager().getPrefix()
                            + t("replay_delete_failed", "<red>Failed to delete replay."));
                }
                player.closeInventory();

                open(player, page, filter);
                return;
            }

            if (event.isLeftClick()) {

                player.closeInventory();
                long requestId = beginRequest(player);
                ReplayRecorder recorder = plugin.getReplayRecorder();
                if (recorder == null) return;
                recorder.loadReplayAsync(replayFile, data ->
                    SchedulerUtil.runForEntity(plugin, player, () -> {
                        if (!player.isOnline() || !isCurrentRequest(player, requestId)) return;
                        if (data != null) {
                            ReplayPlayer replayPlayer = plugin.getReplayPlayer();
                            if (replayPlayer != null) replayPlayer.play(player, data);
                        } else {
                            MessageUtil.send(player, plugin.getConfigManager().getPrefix()
                                    + t("replay_load_failed", "<red>Failed to load replay."));
                        }
                    }, null));
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (openPages.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        openPages.remove(player.getUniqueId());
        viewerReplayFiles.remove(player.getUniqueId());
        openFilters.remove(player.getUniqueId());
        viewerRequests.remove(player.getUniqueId());
    }

    private File[] filterFiles(File[] files, String playerFilter) {
        if (playerFilter == null || playerFilter.isBlank()) return files;
        List<File> filtered = new ArrayList<>();
        for (File file : files) {
            ReplayData.Summary summary = loadSummary(file);
            if (summary != null && summary.getPlayerName().equalsIgnoreCase(playerFilter)) {
                filtered.add(file);
            }
        }
        return filtered.toArray(new File[0]);
    }

    private ReplayData.Summary loadSummary(File file) {
        ReplayFileKey key = ReplayFileKey.of(file);
        Optional<ReplayData.Summary> cached = summaryCache.get(key);
        return cached != null ? cached.orElse(null) : null;
    }

    private void sendNoFiles(Player viewer, String playerFilter) {
        MessageUtil.send(viewer, plugin.getConfigManager().getPrefix()
                + (playerFilter == null || playerFilter.isBlank()
                ? t("replay_no_files", "<yellow>No saved replays found.")
                : t("replay_no_files_player", "<yellow>No saved replays found for <white>{player}<yellow>.",
                "{player}", playerFilter)));
    }

    private long beginRequest(Player viewer) {
        long requestId = requestSequence.incrementAndGet();
        viewerRequests.put(viewer.getUniqueId(), requestId);
        return requestId;
    }

    private boolean isCurrentRequest(Player viewer, long requestId) {
        return viewerRequests.getOrDefault(viewer.getUniqueId(), -1L) == requestId;
    }

    private String t(String key, String fallback, String... replacements) {
        String value = plugin.getConfigManager().msg(key, fallback);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return value;
    }

    private record ReplayFileKey(String path, long lastModified, long length) {
        static ReplayFileKey of(File file) {
            return new ReplayFileKey(file.getAbsolutePath(), file.lastModified(), file.length());
        }
    }
}
