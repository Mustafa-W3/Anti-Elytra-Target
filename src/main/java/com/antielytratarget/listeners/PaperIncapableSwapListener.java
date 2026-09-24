package com.antielytratarget.listeners;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.misc.IncapableSwapCheck;
import com.antielytratarget.netty.PlayerPacketData;
import com.antielytratarget.player.AETPlayer;
import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class PaperIncapableSwapListener implements Listener {

    private final AntiElytraTargetPlugin plugin;

    public PaperIncapableSwapListener(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        if (event.getItemStack() == null
                || event.getItemStack().getType() != Material.FIREWORK_ROCKET) {
            return;
        }

        PlayerPacketData packetData = plugin.getNettyManager() != null
                ? plugin.getNettyManager().getData(player.getUniqueId()) : null;
        if (packetData == null) return;

        long confirmedNanos = System.nanoTime();
        long sequenceId = packetData.silentFireworkState
                .confirmServerElytraBoost(confirmedNanos);
        if (sequenceId < 0L || plugin.isRuntimeSuspendedForTps()) return;

        IncapableSwapCheck check = getCheck(player);
        if (check != null) {
            check.onServerFireworkUse(sequenceId, true, confirmedNanos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onAttackCooldown(PlayerAttackEntityCooldownResetEvent event) {
        if (plugin.isRuntimeSuspendedForTps()) return;
        IncapableSwapCheck check = getCheck(event.getPlayer());
        if (check != null) {
            check.onAttackCooldown(
                    event.getAttackedEntity().getEntityId(),
                    event.getCooledAttackStrength(), System.nanoTime());
        }
    }

    private IncapableSwapCheck getCheck(Player player) {
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        if (aet.checkManager == null) plugin.initPlayerChecks(player);
        return aet.checkManager.getCheck(IncapableSwapCheck.class);
    }
}
