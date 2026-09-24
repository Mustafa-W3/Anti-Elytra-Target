package com.antielytratarget.check.elytra;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.GrimElytraPhysics;
import com.antielytratarget.utils.MathUtils;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

@CheckData(name = "ElytraPhysics", configName = "elytra_physics",
        decay = 0.05, description = "Detects physics-defying elytra movement")
public class ElytraPhysicsCheck extends AbstractCheck {

    private static final int SAMPLES = 20;
    private static final double THRESHOLD = 0.35;
    private static final int CONSEC = 15;

    public ElytraPhysicsCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    public void tick(Player attacker) {
        if (!enabled) return;

        double[] prev = aetPlayer.lastKnownVel;
        if (prev == null) return;
        if (aetPlayer.lastHitTime == 0 || System.currentTimeMillis() - aetPlayer.lastHitTime > 3_000L) return;
        if (aetPlayer.isPostToggleOff() || aetPlayer.isFwBoosted()) return;

        Vector actual = attacker.getVelocity();
        float yaw = attacker.getLocation().getYaw();
        float pitch = attacker.getLocation().getPitch();
        double[] pred = GrimElytraPhysics.simulateWithDrag(prev[0], prev[1], prev[2], yaw, pitch);
        double offset = GrimElytraPhysics.velocityOffset(pred, actual);

        if (plugin.isDebugEnabled()) {
            plugin.debug("[ElytraPhysics] " + attacker.getName()
                    + " offset=" + String.format("%.4f", offset));
        }

        synchronized (aetPlayer.physicsOffsets) {
            aetPlayer.physicsOffsets.add(offset);
            if (aetPlayer.physicsOffsets.size() > SAMPLES) aetPlayer.physicsOffsets.remove(0);
            if (aetPlayer.physicsOffsets.size() < SAMPLES) return;

            double avg = MathUtils.average(aetPlayer.physicsOffsets, 0.0);
            if (avg > THRESHOLD) {
                aetPlayer.physicsStreak++;
                if (aetPlayer.physicsStreak >= CONSEC) {
                    aetPlayer.physicsStreak = 0;
                    aetPlayer.physicsOffsets.clear();
                    java.util.UUID lT = aetPlayer.lastTargetUUID;
                    Player vp = lT != null ? plugin.getServer().getPlayer(lT) : null;
                    flagAndAlert(attacker, vp, avg);
                }
            } else {
                aetPlayer.physicsStreak = 0;
                reward();
            }
        }
    }
}
