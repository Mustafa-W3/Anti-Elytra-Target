package com.antielytratarget.replay;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ReplayData {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

private final UUID playerUUID;
    private final String playerName;
    private final String reason;
    private final LocalDateTime startTime;
    private final int durationSeconds;

private final List<ReplaySnapshot> snapshots;
    private final List<ReplayFlagSnapshot> flagEvents;
    private final List<ReplaySnapshot> snapshotView;
    private final List<ReplayFlagSnapshot> flagEventView;

    public ReplayData(UUID playerUUID, String playerName, String reason, int durationSeconds) {
        this.playerUUID     = playerUUID;
        this.playerName     = playerName;
        this.reason         = reason;
        this.durationSeconds = durationSeconds;
        this.startTime      = LocalDateTime.now();
        this.snapshots      = new ArrayList<>(Math.max(0, durationSeconds * 20));
        this.flagEvents     = new ArrayList<>();
        this.snapshotView   = Collections.unmodifiableList(snapshots);
        this.flagEventView  = Collections.unmodifiableList(flagEvents);
    }

private ReplayData(UUID playerUUID, String playerName, String reason,
                       int durationSeconds, LocalDateTime startTime,
                       List<ReplaySnapshot> snapshots,
                       List<ReplayFlagSnapshot> flagEvents) {
        this.playerUUID     = playerUUID;
        this.playerName     = playerName;
        this.reason         = reason;
        this.durationSeconds = durationSeconds;
        this.startTime      = startTime;
        this.snapshots      = snapshots;
        this.flagEvents     = flagEvents != null ? flagEvents : new ArrayList<>();
        this.snapshotView   = Collections.unmodifiableList(this.snapshots);
        this.flagEventView  = Collections.unmodifiableList(this.flagEvents);
    }

    public void addSnapshot(ReplaySnapshot snapshot) {
        snapshots.add(snapshot);
    }

    public void addFlagEvent(ReplayFlagSnapshot event) {
        if (event != null) flagEvents.add(event);
    }

    public int snapshotCount() {
        return snapshots.size();
    }

public UUID           getPlayerUUID()      { return playerUUID; }
    public String         getPlayerName()      { return playerName; }
    public String         getReason()          { return reason; }
    public LocalDateTime  getStartTime()       { return startTime; }
    public int            getDurationSeconds() { return durationSeconds; }
    public List<ReplaySnapshot> getSnapshots() { return snapshotView; }
    public List<ReplayFlagSnapshot> getFlagEvents() { return flagEventView; }

    public String getFormattedStartTime() {
        return startTime.format(FMT);
    }

public String getFileName() {
        return playerName + "_" + startTime.format(FILE_FMT) + ".replay";
    }

public void saveToFile(File file) throws IOException {
        file.getParentFile().mkdirs();
        File partial = new File(file.getParentFile(), file.getName() + ".part");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(partial))),
                StandardCharsets.UTF_8))) {

            writer.write("HEADER|" + playerUUID + "|" + playerName + "|" +
                    reason + "|" + startTime.format(FMT) + "|" + durationSeconds
                    + "|" + snapshots.size());
            writer.newLine();

            for (ReplayFlagSnapshot flagEvent : flagEvents) {
                writer.write(flagEvent.toLine());
                writer.newLine();
            }

for (ReplaySnapshot snap : snapshots) {
                writer.write(snap.toLine());
                writer.newLine();
            }
        }
        try {
            Files.move(partial.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

public static ReplayData loadFromFile(File file) throws IOException {
        try (BufferedReader reader = openReader(file)) {
            String headerLine = reader.readLine();
            if (headerLine == null || !headerLine.startsWith("HEADER|")) {
                throw new IOException("Invalid replay file: missing header");
            }

            String[] parts = headerLine.split("\\|", 7);
            if (parts.length < 6) {
                throw new IOException("Invalid replay header format");
            }

            UUID playerUUID       = UUID.fromString(parts[1]);
            String playerName     = parts[2];
            String reason         = parts[3];
            LocalDateTime start   = LocalDateTime.parse(parts[4], FMT);
            int durationSec       = Integer.parseInt(parts[5]);

            List<ReplaySnapshot> snapshots = new ArrayList<>();
            List<ReplayFlagSnapshot> flagEvents = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (line.startsWith("FLAG|")) {
                    ReplayFlagSnapshot flag = ReplayFlagSnapshot.fromLine(line);
                    if (flag != null) flagEvents.add(flag);
                    continue;
                }
                ReplaySnapshot snap = ReplaySnapshot.fromLine(line);
                if (snap != null) snapshots.add(snap);
            }

            return new ReplayData(playerUUID, playerName, reason, durationSec, start, snapshots, flagEvents);
        }
    }

    public static Summary loadSummaryFromFile(File file) throws IOException {
        try (BufferedReader reader = openReader(file)) {
            String headerLine = reader.readLine();
            if (headerLine == null || !headerLine.startsWith("HEADER|")) {
                throw new IOException("Invalid replay file: missing header");
            }

            String[] parts = headerLine.split("\\|", 7);
            if (parts.length < 6) {
                throw new IOException("Invalid replay header format");
            }

            UUID playerUUID = UUID.fromString(parts[1]);
            String playerName = parts[2];
            String reason = parts[3];
            LocalDateTime start = LocalDateTime.parse(parts[4], FMT);
            int durationSec = Integer.parseInt(parts[5]);

            if (parts.length >= 7) {
                int snapshots = Integer.parseInt(parts[6]);
                return new Summary(playerUUID, playerName, reason, start, durationSec, snapshots);
            }

            int snapshots = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("FLAG|")) continue;
                snapshots++;
            }

            return new Summary(playerUUID, playerName, reason, start, durationSec, snapshots);
        }
    }

    private static BufferedReader openReader(File file) throws IOException {
        BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
        input.mark(2);
        int first = input.read();
        int second = input.read();
        input.reset();
        InputStream decoded = first == 0x1f && second == 0x8b
                ? new GZIPInputStream(input)
                : input;
        return new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8));
    }

    public static final class Summary {
        private final UUID playerUUID;
        private final String playerName;
        private final String reason;
        private final LocalDateTime startTime;
        private final int durationSeconds;
        private final int snapshotCount;

        private Summary(UUID playerUUID, String playerName, String reason,
                        LocalDateTime startTime, int durationSeconds, int snapshotCount) {
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.reason = reason;
            this.startTime = startTime;
            this.durationSeconds = durationSeconds;
            this.snapshotCount = snapshotCount;
        }

        public UUID getPlayerUUID() { return playerUUID; }
        public String getPlayerName() { return playerName; }
        public String getReason() { return reason; }
        public LocalDateTime getStartTime() { return startTime; }
        public int getDurationSeconds() { return durationSeconds; }
        public int getSnapshotCount() { return snapshotCount; }

        public String getFormattedStartTime() {
            return startTime.format(FMT);
        }
    }
}
