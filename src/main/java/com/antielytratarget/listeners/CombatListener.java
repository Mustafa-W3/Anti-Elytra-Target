package com.antielytratarget.listeners;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.CheckManager;
import com.antielytratarget.check.combat.*;
import com.antielytratarget.check.aim.*;
import com.antielytratarget.check.elytra.*;
import com.antielytratarget.check.misc.*;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

public class CombatListener implements Listener {

    private final AntiElytraTargetPlugin plugin;

    public CombatListener(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;

        if (shouldBlockFireworkAttack(event, attacker)) {
            event.setCancelled(true);
            return;
        }

        if (plugin.isRuntimeSuspendedForTps()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (attacker.equals(victim)) return;

        var recorder = plugin.getReplayRecorder();
        if (recorder != null) {
            recorder.markCombatTarget(attacker, victim);
        }

var cfg = plugin.getConfigManager();
        if (!cfg.isDetectionEnabled()) return;
        if (cfg.isPingExempt(attacker)) return;
        if (cfg.isExemptCreative() && attacker.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        if (cfg.isExemptSpectator() && attacker.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        String bp = cfg.getBypassPermission();
        if (attacker.isPermissionSet(bp) && attacker.hasPermission(bp)) return;

        AETPlayer aet = plugin.getPlayerTracker().get(attacker);
        if (aet.checkManager == null) {
            plugin.initPlayerChecks(attacker);
        }
        CheckManager cm = aet.checkManager;

        boolean isGliding = aet.isOrWasRecentlyGliding();
        if (!isGliding) return;

Location eyeLoc = attacker.getEyeLocation();
        Vector lookDir = eyeLoc.getDirection();
        Vector toVic = victim.getLocation().add(0, victim.getHeight() / 2.0, 0)
                .toVector().subtract(eyeLoc.toVector());
        double angle = MathUtils.angleBetween(lookDir, toVic);
        double yDist = Math.abs(attacker.getLocation().getY() - victim.getLocation().getY());

if (event.isCancelled()) {
            AngleDeviationCheck angleDeviation =
                    cm.getCheck(AngleDeviationCheck.class);
            if (angleDeviation != null) {
                angleDeviation.resetAnalysis();
            }

            RotationPredictionCheck rotationPrediction =
                    cm.getCheck(RotationPredictionCheck.class);
            if (rotationPrediction != null) {
                rotationPrediction.checkOnHit(attacker, victim, angle);
            }
            return;
        }

float yaw = attacker.getLocation().getYaw();
        float pitch = attacker.getLocation().getPitch();
        aet.addRotationSample(yaw, pitch, 100);

float lastYaw = aet.lastHitYaw;
        float lastPitch = aet.lastHitPitch;
        float dYaw = (float) MathUtils.yawDeltaDegrees(lastYaw, yaw);
        float dPitch = Math.abs(pitch - lastPitch);
        if (dYaw > 0 || dPitch > 0) {
            aet.aimProcessor.process(dYaw, dPitch);
        }

cm.getCheck(AttackRateCheck.class).check(attacker);

cm.getCheck(AngleDeviationCheck.class).check(attacker, victim, angle);

if (aet.aimSnapSeeded) {
            cm.getCheck(AimSnapCheck.class).check(attacker, victim, angle);
        }

cm.getCheck(TimingConsistencyCheck.class).check(attacker, victim);

cm.getCheck(ElytraCombatRateCheck.class).check(attacker, victim, angle);

if (victim instanceof Player vp) {
            cm.getCheck(SmoothAimCheck.class).check(attacker, vp);
        }

cm.getCheck(GCDCheck.class).check(attacker);

if (victim instanceof Player vp) {
            cm.getCheck(LookVectorLockCheck.class).check(attacker, vp);
        }

cm.getCheck(PitchLockCheck.class).check(attacker, victim);

cm.getCheck(RotationConsistencyCheck.class).check(attacker, victim, angle);

cm.getCheck(VelocityAlignCheck.class).check(attacker, victim);

        RotationPredictionCheck rotationPrediction = cm.getCheck(RotationPredictionCheck.class);
        if (rotationPrediction != null) {
            rotationPrediction.checkOnHit(attacker, victim, angle);
        }

cm.getCheck(HeadPitchDivergenceCheck.class).check(attacker, victim);

cm.getCheck(OffhandFireworkSwitchCheck.class).checkOnHit(attacker);

aet.aimSnapLastYaw = yaw;
        aet.aimSnapLastPitch = pitch;
        aet.aimSnapLastVicLoc = victim.getLocation().clone();
        aet.aimSnapSeeded = true;

        aet.lastHitYaw = yaw;
        aet.lastHitPitch = pitch;
        aet.lastHitYDist = yDist;
        aet.lastHitAngle = angle;
        aet.lastHitTime = System.currentTimeMillis();
        aet.lastAttackTime = System.currentTimeMillis();

        aet.lastTargetUUID = victim.getUniqueId();

Vector vel = attacker.getVelocity();
        aet.lastKnownVel = new double[]{vel.getX(), vel.getY(), vel.getZ()};
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void enforceFireworkAttackBlock(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (shouldBlockFireworkAttack(event, attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void confirmIncapableSwapDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        AETPlayer aet = plugin.getPlayerTracker().get(attacker);
        if (aet.checkManager == null) return;
        IncapableSwapCheck check =
                aet.checkManager.getCheck(IncapableSwapCheck.class);
        if (check == null) return;

        Material held = attacker.getInventory().getItemInMainHand().getType();
        check.onSuccessfulAttack(
                event.getEntity().getEntityId(), held.getKey().toString(),
                event.getDamage(), event.getFinalDamage(), System.nanoTime());
    }

    private boolean shouldBlockFireworkAttack(EntityDamageByEntityEvent event, Player attacker) {
        return plugin.getConfigManager().isBlockMainhandFireworkAttacks()
                && event.getEntity() instanceof Player
                && attacker.getInventory().getItemInMainHand().getType() == Material.FIREWORK_ROCKET;
    }
}
