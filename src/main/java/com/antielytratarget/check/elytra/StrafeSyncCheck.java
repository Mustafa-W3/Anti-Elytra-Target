package com.antielytratarget.check.elytra;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@CheckData(name = "StrafeSync", configName = "strafe_sync",
        decay = 0.05, description = "Detects velocity-to-look lock during glide")
public class StrafeSyncCheck extends AbstractCheck {

    private double maxDeviation = 0.15;
    private int sampleSize = 20;
    private double approachRateSkip = 0.3;

    public StrafeSyncCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        maxDeviation = cfg("max_deviation", 0.15);
        sampleSize = cfgInt("sample_size", 20);
        approachRateSkip = cfg("approach_rate_skip", 0.3);
    }

    public void check(Player attacker) {
        if (!enabled) return;

        java.util.UUID lvU = aetPlayer.lastTargetUUID;
        if (lvU == null) { clearSamples(); return; }

        Player lv = plugin.getServer().getPlayer(lvU);
        if (lv == null) { clearSamples(); return; }
        if (aetPlayer.isOrWasRecentlyGliding() && lv.isGliding()) { clearSamples(); return; }

Vector vv = lv.getVelocity();
        if (vv.getX() * vv.getX() + vv.getZ() * vv.getZ() > 0.0625) { clearSamples(); return; }

Location attackerLocation = attacker.getLocation();
        Location victimLocation = lv.getLocation();
        if (attackerLocation.getWorld() == null
                || !attackerLocation.getWorld().equals(victimLocation.getWorld())) {
            aetPlayer.lastDistToVictim = 0.0;
            clearSamples();
            return;
        }

        double cd = attackerLocation.distance(victimLocation);
        double pd = aetPlayer.lastDistToVictim;
        aetPlayer.lastDistToVictim = cd;
        if (pd > 0 && (pd - cd) > approachRateSkip) { clearSamples(); return; }

        Vector vel = attacker.getVelocity();
        Vector lk = attacker.getEyeLocation().getDirection();
        double velX = vel.getX();
        double velZ = vel.getZ();
        double lookX = lk.getX();
        double lookZ = lk.getZ();
        double velLenSq = velX * velX + velZ * velZ;
        double lookLenSq = lookX * lookX + lookZ * lookZ;
        if (velLenSq < 0.04 || lookLenSq < 0.001) return;

        double velInvLen = 1.0 / Math.sqrt(velLenSq);
        double lookInvLen = 1.0 / Math.sqrt(lookLenSq);
        double dx = velX * velInvLen - lookX * lookInvLen;
        double dz = velZ * velInvLen - lookZ * lookInvLen;
        double deviation = Math.sqrt(dx * dx + dz * dz);
        synchronized (aetPlayer.strafeSyncSamples) {
            aetPlayer.strafeSyncSamples.add(deviation);
            while (aetPlayer.strafeSyncSamples.size() > sampleSize) aetPlayer.strafeSyncSamples.remove(0);

            if (aetPlayer.strafeSyncSamples.size() < sampleSize) return;

            double mean = MathUtils.average(aetPlayer.strafeSyncSamples, 1.0);
            double std = MathUtils.standardDeviation(aetPlayer.strafeSyncSamples);

            if (mean < maxDeviation && std < maxDeviation) {
                clearSamples();
                flagAndAlert(attacker, lv, mean);
            } else {
                reward();
            }
        }
    }

    private void clearSamples() {
        aetPlayer.strafeSyncSamples.clear();
    }
}
