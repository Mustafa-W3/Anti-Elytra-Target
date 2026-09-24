package com.antielytratarget.replay;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class ReplayFlagSnapshot {

    private final int frameIndex;
    private final long tickOffset;
    private final String checkName;
    private final int totalFlags;

    public ReplayFlagSnapshot(int frameIndex, long tickOffset, String checkName, int totalFlags) {
        this.frameIndex = Math.max(0, frameIndex);
        this.tickOffset = Math.max(0L, tickOffset);
        this.checkName = checkName == null || checkName.isBlank() ? "Unknown" : checkName;
        this.totalFlags = Math.max(0, totalFlags);
    }

    public int getFrameIndex() { return frameIndex; }
    public long getTickOffset() { return tickOffset; }
    public String getCheckName() { return checkName; }
    public int getTotalFlags() { return totalFlags; }

    public String toLine() {
        return "FLAG|" + frameIndex + "|" + tickOffset + "|" + totalFlags + "|" + encode(checkName);
    }

    public static ReplayFlagSnapshot fromLine(String line) {
        if (line == null || !line.startsWith("FLAG|")) return null;
        String[] parts = line.split("\\|", 5);
        if (parts.length < 5) return null;
        try {
            return new ReplayFlagSnapshot(
                    Integer.parseInt(parts[1]),
                    Long.parseLong(parts[2]),
                    decode(parts[4]),
                    Integer.parseInt(parts[3])
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        String padded = value + "=".repeat((4 - value.length() % 4) % 4);
        return new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
    }
}
