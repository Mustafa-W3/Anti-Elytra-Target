package com.antielytratarget.models;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FlagLogEntry {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^\\[([^]]+)](?: \\[FLAG])? PLAYER=(\\S+)\\s+"
                    + "(?:UUID=(\\S+)\\s+)?CHECK=(\\S+)\\s+"
                    + "FLAGS=(\\d+)\\s+VALUE=(\\S+)\\s+"
                    + "VICTIM=(.*?)\\s+LOCATION=(.*)$");

    private final String        playerName;
    private final UUID          playerUUID;
    private final String        checkName;
    private final int           flagCount;
    private final String        location;
    private final String        victimName;
    private final LocalDateTime timestamp;
    private final double        detectionValue;

    public FlagLogEntry(String playerName, UUID playerUUID, String checkName,
                        int flagCount, String location, String victimName,
                        double detectionValue) {
        this(playerName, playerUUID, checkName, flagCount, location, victimName,
                LocalDateTime.now(), detectionValue);
    }

    private FlagLogEntry(String playerName, UUID playerUUID, String checkName,
                         int flagCount, String location, String victimName,
                         LocalDateTime timestamp, double detectionValue) {
        this.playerName     = playerName;
        this.playerUUID     = playerUUID;
        this.checkName      = checkName;
        this.flagCount      = flagCount;
        this.location       = location;
        this.victimName     = victimName;
        this.timestamp      = timestamp;
        this.detectionValue = detectionValue;
    }

public String        getPlayerName()      { return playerName; }
    public UUID          getPlayerUUID()      { return playerUUID; }
    public String        getCheckName()       { return checkName; }
    public int           getFlagCount()       { return flagCount; }
    public String        getLocation()        { return location; }
    public String        getVictimName()      { return victimName; }
    public LocalDateTime getTimestamp()       { return timestamp; }
    public double        getDetectionValue()  { return detectionValue; }

    public String getFormattedTimestamp() {
        return timestamp.format(DISPLAY_FMT);
    }

public String toLogLine() {
        return String.format(Locale.ROOT,
                "[%s] [FLAG] PLAYER=%s CHECK=%s FLAGS=%d VALUE=%.4f VICTIM=%s LOCATION=%s",
                getFormattedTimestamp(),
                singleLine(playerName),
                singleLine(checkName), flagCount,
                detectionValue,
                singleLine(victimName != null ? victimName : "N/A"),
                singleLine(location != null ? location : "?"));
    }

public String toDetailedLogLine(String debugDetails) {
        if (debugDetails == null || debugDetails.isBlank()) return toLogLine();
        return toLogLine() + " | DEBUG " + singleLine(debugDetails);
    }

public static FlagLogEntry fromLogLine(
            String line, Function<String, UUID> uuidResolver) {
        if (line == null || line.isBlank()) return null;
        int debugStart = line.indexOf(" | DEBUG ");
        String baseLine = debugStart >= 0 ? line.substring(0, debugStart) : line;
        Matcher matcher = LOG_PATTERN.matcher(baseLine);
        if (!matcher.matches()) return null;

        try {
            LocalDateTime timestamp = LocalDateTime.parse(matcher.group(1), DISPLAY_FMT);
            String parsedPlayerName = matcher.group(2);
            String legacyUuid = matcher.group(3);
            UUID uuid = legacyUuid != null
                    ? UUID.fromString(legacyUuid)
                    : uuidResolver.apply(parsedPlayerName);
            if (uuid == null) return null;

            String check = matcher.group(4);
            int flags = Integer.parseInt(matcher.group(5));
            double value = Double.parseDouble(matcher.group(6));
            String victim = matcher.group(7).trim();
            String parsedLocation = matcher.group(8).trim();
            return new FlagLogEntry(
                    parsedPlayerName, uuid, check, flags, parsedLocation, victim,
                    timestamp, value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

public String toDisplayLine() {
        return "<gray>[" + getFormattedTimestamp() + "] "
                + "<yellow>Check: <red>" + checkName
                + " <gray>| Flags: <red>"   + flagCount
                + " <gray>| Victim: <yellow>"  + (victimName != null ? victimName : "N/A")
                + " <gray>| At: <white>"      + (location   != null ? location   : "?")
                + " <gray>| Val: <green>"     + String.format(Locale.ROOT, "%.3f", detectionValue);
    }
}
