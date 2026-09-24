package com.antielytratarget.check.misc;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.check.AbstractCheck;
import com.antielytratarget.check.CheckData;
import com.antielytratarget.player.AETPlayer;
import com.antielytratarget.utils.ClientVersionExemptions;
import com.antielytratarget.utils.MathUtils;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.entity.Player;

@CheckData(name = "UseItemRotationMismatch", configName = "use_item_rotation_mismatch",
        decay = 0.05,
        description = "Detects USE_ITEM packet rotation that does not match tick rotation")
public class UseItemRotationMismatchCheck extends AbstractCheck {

    private final RotationMismatchEvidence mismatchEvidence =
            new RotationMismatchEvidence();
    private boolean requireGliding = false;
    private boolean requireFirework = true;
    private long minIntervalMs = 0L;
    private long lastFlagMs = 0L;

    public UseItemRotationMismatchCheck(AntiElytraTargetPlugin plugin, AETPlayer aetPlayer) {
        super(plugin, aetPlayer);
    }

    @Override
    protected void loadConfig() {
        requireGliding = cfgBool("require_gliding", true);
        requireFirework = cfgBool("require_firework", true);
        minIntervalMs = Math.max(0L, cfgInt("min_interval_ms", 0));
    }

    @Override
    public synchronized void onReload() {
        super.onReload();
        mismatchEvidence.reset();
        lastFlagMs = 0L;
    }

    public void onRotationMatch(long packetTick) {
        mismatchEvidence.recordValid(packetTick);
    }

    public synchronized void flagMismatch(Player player,
                                          ClientVersion clientVersion,
                                          float useItemYaw, float useItemPitch,
                                          float tickYaw, float tickPitch,
                                          long packetTick,
                                          boolean fireworkAtUse) {
        if (!canCheck(player)) return;
        if (ClientVersionExemptions.isUseItemRotationMismatchExempt(clientVersion)) return;
        if (isBedrockExempt()) return;
        if (requireGliding && !aetPlayer.isOrWasRecentlyGliding()) return;
        if (requireFirework && !fireworkAtUse) return;

        double value;
        if (Float.isFinite(useItemYaw) && Float.isFinite(useItemPitch)
                && Float.isFinite(tickYaw) && Float.isFinite(tickPitch)) {
            double yawDelta = Math.abs(MathUtils.yawDeltaDegrees(useItemYaw, tickYaw));
            double pitchDelta = Math.abs(useItemPitch - tickPitch);
            value = Math.max(yawDelta, pitchDelta);
        } else {
            value = 999.0;
        }

        RotationMismatchEvidence.Observation observation =
                mismatchEvidence.record(packetTick, value);

        if (plugin.isDebugEnabled()) {
            plugin.debug("[UseItemRotationMismatch] " + player.getName()
                    + " packetYaw=" + useItemYaw
                    + " packetPitch=" + useItemPitch
                    + " tickYaw=" + tickYaw
                    + " tickPitch=" + tickPitch
                    + " packetTick=" + packetTick
                    + " evidence=" + observation.count() + "/"
                    + RotationMismatchEvidence.REQUIRED_MISMATCHES);
        }

        if (!observation.ready()) return;

        value = observation.maxDelta();
        long now = System.currentTimeMillis();
        if (minIntervalMs > 0 && now - lastFlagMs < minIntervalMs) return;
        lastFlagMs = now;

        flagAndAlert(player, null, value);
    }
}
