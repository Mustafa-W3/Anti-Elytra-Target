package com.antielytratarget.managers;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.models.FlagLogEntry;
import com.antielytratarget.utils.SchedulerUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogManager {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AntiElytraTargetPlugin plugin;
    private final ConfigManager          config;

    private BufferedWriter flagWriter;
    private SchedulerUtil.TaskWrapper autoFlushTask;

    public LogManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        openWriters();
        scheduleAutoFlush();
    }

public void log(FlagLogEntry entry, String debugDetails) {
        if (!config.isFileLoggingEnabled()) return;
        String line = config.isDebugMode()
                ? entry.toDetailedLogLine(debugDetails)
                : entry.toLogLine();
        writeLine(flagWriter, line);
    }

public void logInfo(String message) {
        if (!config.isFileLoggingEnabled()) return;
        writeLine(flagWriter, "[" + now() + "] [INFO] " + message);
    }

public synchronized void flushLogs() {
        flush(flagWriter);
    }

public synchronized void close() {
        if (autoFlushTask != null) {
            autoFlushTask.cancel();
            autoFlushTask = null;
        }
        flushLogs();
        closeQuietly(flagWriter);
        flagWriter = null;
    }

public synchronized void reload() {
        close();
        openWriters();
        scheduleAutoFlush();
    }

public Path getLogPath() {
        return resolveLogPath(config.getLogFilePath());
    }

private void openWriters() {
        try {
            Path logPath = getLogPath();
            ensureParent(logPath);
            flagWriter = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(logPath,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    StandardCharsets.UTF_8));

            logInfo("=== AntiElytraTarget log session started ===");
        } catch (IOException e) {
            plugin.getLogger().severe("[AET] Failed to open flags.log: " + e.getMessage());
        }
    }

    private void scheduleAutoFlush() {
        if (autoFlushTask != null) autoFlushTask.cancel();
        autoFlushTask = SchedulerUtil.runAsyncTimer(plugin, this::flushLogs, 20L * 30, 20L * 30);
    }

    private synchronized void writeLine(BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {

            plugin.getLogger().warning("[AET-LOG-FALLBACK] " + line);
        }
    }

    private void flush(BufferedWriter writer) {
        if (writer == null) return;
        try { writer.flush(); } catch (IOException ignored) {}
    }

    private void closeQuietly(BufferedWriter writer) {
        if (writer == null) return;
        try { writer.close(); } catch (IOException ignored) {}
    }

    private Path resolveLogPath(String configPath) {
        Path p = Paths.get(configPath);
        if (p.isAbsolute()) return p;
        return plugin.getDataFolder().toPath().resolve(configPath).normalize();
    }

    private void ensureParent(Path path) {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            try { Files.createDirectories(parent); }
            catch (IOException e) {
                plugin.getLogger().severe("[AET] Cannot create log directory: " + e.getMessage());
            }
        }
    }

    private String now() {
        return LocalDateTime.now().format(TS_FMT);
    }
}
