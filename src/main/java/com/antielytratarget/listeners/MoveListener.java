package com.antielytratarget.listeners;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.CheckManager;
import com.antielytratarget.check.aim.AimComplexCheck;
import com.antielytratarget.check.aim.AimMXHeuristicCheck;
import com.antielytratarget.check.combat.AimSnapCheck;
import com.antielytratarget.check.combat.AngleDeviationCheck;
import com.antielytratarget.check.elytra.*;
import com.antielytratarget.check.misc.IncapableSwapCheck;
import com.antielytratarget.player.AETPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;

public class MoveListener implements Listener {

    private final AntiElytraTargetPlugin plugin;

    public MoveListener(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        aet.setCurrentlyGliding(player.isGliding());
        aet.setInsideVehicle(player.isInsideVehicle());
        if (plugin.getNettyManager() != null) {
            plugin.getNettyManager().getData(player.getUniqueId())
                    .silentFireworkState.setGliding(player.isGliding());
        }
        if (!player.isGliding()) return;
        if (plugin.isRuntimeSuspendedForTps()) return;

        if (aet.checkManager == null) plugin.initPlayerChecks(player);

        aet.markRecentlyGliding();

        Location to = event.getTo();
        if (to != null) {
            Location snapshot = to.clone();
synchronized (aet.posHistory) {
                aet.posHistory.addLast(snapshot);
                while (aet.posHistory.size() > 20) aet.posHistory.removeFirst();
            }
        }

        Vector vel = player.getVelocity();
        aet.lastKnownVel = new double[]{vel.getX(), vel.getY(), vel.getZ()};
        if (plugin.getConfigManager().isPingExempt(player)) return;

CheckManager cm = aet.checkManager;
        RotationPredictionCheck rotCheck = cm.getCheck(RotationPredictionCheck.class);
        if (rotCheck != null) {
            rotCheck.tick(player, event.getFrom(), event.getTo(), event instanceof PlayerTeleportEvent);
        }

cm.getCheck(StrafeSyncCheck.class).check(player);
        cm.getCheck(AccelerationLockCheck.class).check(player);
        cm.getCheck(ElytraPhysicsCheck.class).tick(player);
    }

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRotation(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;

Location from = event.getFrom();
        if (to.getYaw() == from.getYaw() && to.getPitch() == from.getPitch()) return;
        if (plugin.isRuntimeSuspendedForTps()) return;

        Player player = event.getPlayer();
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        if (plugin.getConfigManager().isPingExempt(player)) {
            aet.prevTickYaw = Float.NaN;
            aet.prevTickPitch = Float.NaN;
            return;
        }
        if (aet.checkManager == null) plugin.initPlayerChecks(player);

        CheckManager cm = aet.checkManager;
        float curYaw = to.getYaw();
        float curPitch = to.getPitch();

if (Float.isNaN(aet.prevTickYaw)) {
            aet.prevTickYaw = curYaw;
            aet.prevTickPitch = curPitch;
            return;
        }

float rawDeltaYaw = curYaw - aet.prevTickYaw;

        rawDeltaYaw = ((rawDeltaYaw % 360) + 540) % 360 - 180;
        float deltaYaw = rawDeltaYaw;
        float deltaPitch = curPitch - aet.prevTickPitch;
        float absDeltaYaw = Math.abs(deltaYaw);
        float absDeltaPitch = Math.abs(deltaPitch);

if (absDeltaPitch > 0) {
            aet.mxSensitivity.feedDelta(absDeltaPitch);
        }

if (aet.isOrWasRecentlyGliding() && player.getVelocity().lengthSquared() > 0.09) {
            double totalDelta = Math.sqrt(absDeltaYaw * absDeltaYaw + absDeltaPitch * absDeltaPitch);
            if (totalDelta > 0.1 && totalDelta < AETPlayer.APPROACH_TRACKING_MAX_DELTA) {
                aet.approachTrackingTicks = Math.min(aet.approachTrackingTicks + 1, 40);
            } else if (totalDelta >= AETPlayer.APPROACH_TRACKING_MAX_DELTA) {

                aet.approachTrackingTicks = Math.max(0, aet.approachTrackingTicks - 3);
            }

        } else {

            aet.approachTrackingTicks = Math.max(0, aet.approachTrackingTicks - 2);
        }

long msSinceAttack = System.currentTimeMillis() - aet.lastAttackTime;
        if (msSinceAttack < 3500 && (absDeltaYaw > 0 || absDeltaPitch > 0)) {
            AimSnapCheck aimSnapCheck = cm.getCheck(AimSnapCheck.class);
            if (aimSnapCheck != null) aimSnapCheck.tick(absDeltaYaw, absDeltaPitch);

            AngleDeviationCheck angleDeviationCheck = cm.getCheck(AngleDeviationCheck.class);
            if (angleDeviationCheck != null) angleDeviationCheck.tick(deltaYaw, deltaPitch);

            AimComplexCheck complexCheck = cm.getCheck(AimComplexCheck.class);
            if (complexCheck != null) complexCheck.tick(absDeltaYaw, absDeltaPitch);

            AimMXHeuristicCheck heuristicCheck = cm.getCheck(AimMXHeuristicCheck.class);
            if (heuristicCheck != null) {
                heuristicCheck.tick(deltaYaw, deltaPitch, absDeltaYaw, absDeltaPitch, curPitch);
            }
        }

        aet.prevTickYaw = curYaw;
        aet.prevTickPitch = curPitch;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideToggle(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (plugin.getNettyManager() != null) {
            plugin.getNettyManager().getData(player.getUniqueId())
                    .silentFireworkState.setGliding(event.isGliding());
        }

        AETPlayer aet = plugin.getPlayerTracker().get(player);
        aet.setCurrentlyGliding(event.isGliding());

        if (event.isGliding()) {

            aet.lastToggleOnTime = System.currentTimeMillis();
            aet.markRecentlyGliding();
            aet.lastKnownVel = null;
            aet.physicsStreak = 0;
            aet.physicsOffsets.clear();
            aet.accelSamples.clear();
            aet.strafeSyncSamples.clear();

if (aet.checkManager != null) {
                RotationPredictionCheck rc = aet.checkManager.getCheck(RotationPredictionCheck.class);
                if (rc != null) rc.hardSuppressFor(player, 750L);

                AngleDeviationCheck angleDeviation =
                        aet.checkManager.getCheck(AngleDeviationCheck.class);
                if (angleDeviation != null) angleDeviation.resetAnalysis();

AimMXHeuristicCheck hc = aet.checkManager.getCheck(AimMXHeuristicCheck.class);
                if (hc != null) hc.resetAllBuffers();
            }
        } else {

            aet.lastToggleOffTime = System.currentTimeMillis();

            aet.aimSnapSeeded = false;

if (aet.checkManager != null) {
                RotationPredictionCheck rc = aet.checkManager.getCheck(RotationPredictionCheck.class);
                if (rc != null) rc.hardSuppressFor(player, 750L);

                AngleDeviationCheck angleDeviation =
                        aet.checkManager.getCheck(AngleDeviationCheck.class);
                if (angleDeviation != null) angleDeviation.resetAnalysis();

                AimMXHeuristicCheck hc = aet.checkManager.getCheck(AimMXHeuristicCheck.class);
                if (hc != null) hc.resetAllBuffers();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        resetIncapableSwap(event.getPlayer());
        suppressRotation(event.getPlayer(), 1_000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        resetIncapableSwap(event.getPlayer());
        suppressRotation(event.getPlayer(), 1_000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        resetIncapableSwap(event.getPlayer());
        suppressRotation(event.getPlayer(), 1_000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            plugin.getPlayerTracker().get(player).setInsideVehicle(true);
            suppressRotation(player, 1_000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            plugin.getPlayerTracker().get(player).setInsideVehicle(false);
            suppressRotation(player, 1_000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        aet.lastDamageTime = System.currentTimeMillis();

}

    private void suppressRotation(Player player, long milliseconds) {
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        aet.prevTickYaw = Float.NaN;
        aet.prevTickPitch = Float.NaN;

        if (aet.checkManager == null) return;

        RotationPredictionCheck check = aet.checkManager.getCheck(RotationPredictionCheck.class);
        if (check != null) check.hardSuppressFor(player, milliseconds);

        AngleDeviationCheck angleDeviation =
                aet.checkManager.getCheck(AngleDeviationCheck.class);
        if (angleDeviation != null) angleDeviation.resetAnalysis();
    }

    private void resetIncapableSwap(Player player) {
        AETPlayer aet = plugin.getPlayerTracker().get(player);
        if (aet.checkManager == null) return;
        IncapableSwapCheck check =
                aet.checkManager.getCheck(IncapableSwapCheck.class);
        if (check != null) check.resetState();
    }
}
