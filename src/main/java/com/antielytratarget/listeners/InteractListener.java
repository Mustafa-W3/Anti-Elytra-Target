package com.antielytratarget.listeners;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.misc.FireworkPatternCheck;
import com.antielytratarget.check.misc.OffhandFireworkSwitchCheck;
import com.antielytratarget.netty.PlayerPacketData;
import com.antielytratarget.player.AETPlayer;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import org.bukkit.Material;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.FireworkExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;

public class InteractListener implements Listener {

    private final AntiElytraTargetPlugin plugin;

    public InteractListener(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

@EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.initPlayerChecks(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {

if (plugin.removePlayerChecks(event.getPlayer())) {
            plugin.getFlagManager().removePlayer(event.getPlayer().getUniqueId());
        }
    }

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        boolean firework = isFirework(event.getItem());
        boolean useAllowed = event.useItemInHand() != Event.Result.DENY;
        AETPlayer aet = plugin.getPlayerTracker().get(p);
        if (aet.checkManager == null) plugin.initPlayerChecks(p);
        PlayerPacketData packetData = plugin.getNettyManager() != null
                ? plugin.getNettyManager().getData(p.getUniqueId()) : null;
        if (packetData != null) {
            InteractionHand hand = event.getHand() == EquipmentSlot.OFF_HAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (isUseItemInteraction(event.getAction())) {
                packetData.silentFireworkState.onServerItemInteraction(
                        hand == InteractionHand.MAIN_HAND,
                        firework, useAllowed, System.nanoTime());
                if (event.getAction() == Action.RIGHT_CLICK_AIR) {
                    packetData.confirmUseItem(hand, useAllowed && firework);
                }
            }
        }

        if (!useAllowed || plugin.isRuntimeSuspendedForTps()) return;
        if (!p.isGliding()) return;

        if (firework) {
            aet.confirmRealBoost();
            aet.lastFwBoostTime = System.currentTimeMillis();
            if (plugin.getConfigManager().isPingExempt(p)) return;
            OffhandFireworkSwitchCheck offhand =
                    aet.checkManager.getCheck(OffhandFireworkSwitchCheck.class);
            if (offhand != null) {
                offhand.onFireworkUse();
            }
        }
    }

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireworkExplode(FireworkExplodeEvent event) {
        if (plugin.isRuntimeSuspendedForTps()) return;
        Firework fw = event.getEntity();
        if (fw.getShooter() instanceof Player owner) {
            AETPlayer aet = plugin.getPlayerTracker().get(owner);
            if (aet.checkManager == null) plugin.initPlayerChecks(owner);

            aet.confirmRealBoost();
            aet.lastFwBoostTime = System.currentTimeMillis();
            if (plugin.getConfigManager().isPingExempt(owner)) return;
            aet.checkManager.getCheck(FireworkPatternCheck.class).onFireworkExplode(owner);
        }
    }

@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (plugin.getConfigManager().isBlockOffhandFirework() && isFirework(event.getOffHandItem())) {
            event.setCancelled(true);
            return;
        }

        if (plugin.isRuntimeSuspendedForTps()) return;
        Player p = event.getPlayer();
        if (!p.isGliding()) return;

        AETPlayer aet = plugin.getPlayerTracker().get(p);
        if (aet.checkManager == null) plugin.initPlayerChecks(p);

        var ofw = aet.checkManager.getCheck(OffhandFireworkSwitchCheck.class);

        boolean mainIsFirework = event.getMainHandItem() != null &&
                event.getMainHandItem().getType() == Material.FIREWORK_ROCKET;
        boolean offIsFirework = event.getOffHandItem() != null &&
                event.getOffHandItem().getType() == Material.FIREWORK_ROCKET;

if (offIsFirework && !mainIsFirework) {
            ofw.onSwapToFirework();
        }

        if (mainIsFirework && !offIsFirework) {
            ofw.onSwapFromFirework();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOffhandInventoryClick(InventoryClickEvent event) {
        if (!plugin.getConfigManager().isBlockOffhandFirework()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            if (isFirework(event.getCurrentItem())) {
                event.setCancelled(true);
            }
            return;
        }

        if (!isOffhandSlot(event)) return;

        ItemStack hotbarItem = null;
        int hotbarButton = event.getHotbarButton();
        if (hotbarButton >= 0 && hotbarButton <= 8) {
            hotbarItem = player.getInventory().getItem(hotbarButton);
        }

        if (isFirework(event.getCursor()) || isFirework(event.getCurrentItem()) || isFirework(hotbarItem)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onOffhandInventoryDrag(InventoryDragEvent event) {
        if (!plugin.getConfigManager().isBlockOffhandFirework()) return;
        if (!isOffhandSlot(event)) return;
        if (isFirework(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        if (plugin.isRuntimeSuspendedForTps()) return;
        Player p = event.getPlayer();
        if (plugin.getConfigManager().isPingExempt(p)) return;
        if (!p.isGliding()) return;

        AETPlayer aet = plugin.getPlayerTracker().get(p);
        if (aet.checkManager == null) plugin.initPlayerChecks(p);

Material prev = p.getInventory().getItem(event.getPreviousSlot()) != null ?
                p.getInventory().getItem(event.getPreviousSlot()).getType() : Material.AIR;
        if (prev == Material.FIREWORK_ROCKET) {
            aet.confirmRealBoost();
        }
    }

    private boolean isOffhandSlot(InventoryClickEvent event) {
        return event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 40;
    }

    private boolean isOffhandSlot(InventoryDragEvent event) {
        for (int rawSlot : event.getRawSlots()) {
            if (event.getView().getInventory(rawSlot) instanceof PlayerInventory
                    && event.getView().convertSlot(rawSlot) == 40) {
                return true;
            }
        }
        return false;
    }

    private boolean isFirework(ItemStack item) {
        return item != null && item.getType() == Material.FIREWORK_ROCKET;
    }

    private boolean isUseItemInteraction(Action action) {
        return action == Action.RIGHT_CLICK_AIR
                || action == Action.RIGHT_CLICK_BLOCK;
    }
}
