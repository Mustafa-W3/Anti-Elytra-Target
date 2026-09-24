package com.antielytratarget.utils;

import org.bukkit.command.CommandSender;

public final class PermissionUtil {

    public static final String ADMIN = "aet.admin";
    public static final String LEGACY_ADMIN = "antielytratarget.op";

    public static final String NOTIFY = "aet.notify";
    public static final String LEGACY_NOTIFY = "antielytratarget.notify";
    public static final String SUSPECT = "aet.suspect";
    public static final String LEGACY_SUSPECT = "antielytratarget.suspect";
    public static final String REPLAY_WATCH = "aet.replaywatch";
    public static final String REPLAY_SPAWN = "aet.replayspawn";
    public static final String REPLAY_DELETE = "aet.replaydelete";
    public static final String REPLAY_RECORD = "aet.replayrecord";
    public static final String TPA = "aet.tpa";
    public static final String PROFILE = "aet.profile";
    public static final String CLEAR = "aet.clear";
    public static final String STATUS = "aet.status";
    public static final String RELOAD = "aet.reload";
    public static final String LOG = "aet.log";

    private PermissionUtil() {}

    public static boolean isAdmin(CommandSender sender) {
        return sender != null
                && (sender.isOp()
                || sender.hasPermission(ADMIN)
                || sender.hasPermission(LEGACY_ADMIN));
    }

    public static boolean has(CommandSender sender, String permission) {
        return sender != null
                && (isAdmin(sender)
                || sender.hasPermission(permission));
    }

    public static boolean hasAny(CommandSender sender, String... permissions) {
        if (isAdmin(sender)) return true;
        if (sender == null) return false;
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) return true;
        }
        return false;
    }

    public static boolean canReceiveAlerts(CommandSender sender) {
        return hasAny(sender, NOTIFY, LEGACY_NOTIFY);
    }

    public static boolean canWatchReplay(CommandSender sender) {
        return hasAny(sender, REPLAY_WATCH, REPLAY_SPAWN);
    }
}
