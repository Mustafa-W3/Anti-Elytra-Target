package com.antielytratarget.check.combat;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@CheckData(name = "ElytraCombatRate", configName = "elytra_combat_rate",
        decay = 0.15, description = "Detects impossible rotation for elytra hit")
public class ElytraCombatRateCheck extends AbstractCheck {

private double maxRotPerTickAtSpeed = 40.0;
    private double speedThreshold = 0.5;

    public ElytraCombatRateCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxRotPerTickAtSpeed = cfg("max_rot_per_tick", 40.0);
        speedThreshold = cfg("speed_threshold", 0.5);
    }

public boolean check(Player attacker, LivingEntity victim, double curAngle) {
        if (!enabled) return false;
        if (aetPlayer.isPostToggleOff()) return false;
        if (aetPlayer.isFwBoosted()) return false;

        double speed = attacker.getVelocity().length();
        if (speed < speedThreshold) {
            reward();
            return false;
        }

float prevYaw = aetPlayer.lastCombatYaw;
        float prevPitch = aetPlayer.lastCombatPitch;
        long lastHitTime = aetPlayer.lastHitTime;
        long now = System.currentTimeMillis();
        long dt = now - lastHitTime;

if (lastHitTime == 0 || dt > 2000) {
            seedState(attacker);
            reward();
            return false;
        }

float curYaw = attacker.getLocation().getYaw();
        float curPitch = attacker.getLocation().getPitch();
        double dYaw = MathUtils.yawDeltaDegrees(prevYaw, curYaw);
        double actualRotation = Math.sqrt(dYaw * dYaw + Math.pow(curPitch - prevPitch, 2));

Location eye = attacker.getEyeLocation();
        double yawRad = Math.toRadians(prevYaw);
        double pitchRad = Math.toRadians(prevPitch);
        Vector prevLook = new Vector(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad)).normalize();
        Vector toVic = victim.getLocation().add(0, victim.getHeight() / 2.0, 0)
                .toVector().subtract(eye.toVector());
        double dist = toVic.length();
        if (dist < 0.5) {
            seedState(attacker);
            reward();
            return false;
        }
        toVic.normalize();
        double requiredRotation = MathUtils.angleBetween(prevLook, toVic);

double ticks = Math.max(1.0, dt / 50.0);

double speedFactor = Math.max(0.5, 1.0 - (speed - speedThreshold) * 0.3);
        double maxAchievable = maxRotPerTickAtSpeed * ticks * speedFactor;

        if (plugin.isDebugEnabled()) {
            plugin.debug("[ElytraCombatRate] " + attacker.getName()
                    + " actual=" + String.format("%.1f", actualRotation)
                    + " required=" + String.format("%.1f", requiredRotation)
                    + " maxAchievable=" + String.format("%.1f", maxAchievable)
                    + " speed=" + String.format("%.2f", speed)
                    + " dt=" + dt + "ms curAngle=" + String.format("%.2f", curAngle));
        }

        boolean flagged = false;

if (actualRotation > maxAchievable && curAngle < 3.0) {
            flagged = flagAndAlert(attacker, victim, actualRotation);
        }

        else if (requiredRotation > maxAchievable && curAngle < 2.0 && actualRotation > requiredRotation * 0.8) {
            flagged = flagAndAlert(attacker, victim, requiredRotation);
        }

        seedState(attacker);
        if (!flagged) reward();
        return flagged;
    }

    private void seedState(Player attacker) {
        aetPlayer.lastCombatYaw = attacker.getLocation().getYaw();
        aetPlayer.lastCombatPitch = attacker.getLocation().getPitch();
    }
}
