package com.antielytratarget.models;

import java.util.UUID;

public final class FlagLogEntryRegressionSimulation {

    private FlagLogEntryRegressionSimulation() {
    }

    public static void main(String[] args) {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        FlagLogEntry source = new FlagLogEntry(
                "TestPlayer", uuid, "PacketOrder", 3,
                "world x=1.0 y=64.0 z=-2.0", "Target", 1.25);

        String normal = source.toLogLine();
        assertFalse(normal.contains(uuid.toString()), "Normal log leaked UUID");
        assertTrue(normal.contains("[FLAG] PLAYER=TestPlayer"), "Flag marker missing");

        String detailed = source.toDetailedLogLine(
                "WINDOW=2 STAGE=TEST PING=42ms TPS=(20.00,19.99,19.98)");
        assertFalse(detailed.contains(uuid.toString()), "Detailed log leaked UUID");
        assertTrue(detailed.contains("| DEBUG WINDOW=2"), "Debug details missing");

        FlagLogEntry parsed = FlagLogEntry.fromLogLine(detailed, ignored -> uuid);
        assertNotNull(parsed, "Detailed flags.log line did not parse");
        assertEquals(uuid, parsed.getPlayerUUID(), "Resolved UUID was not retained in memory");
        assertEquals("TestPlayer", parsed.getPlayerName(), "Player name changed");
        assertEquals("PacketOrder", parsed.getCheckName(), "Check name changed");
        assertEquals(3, parsed.getFlagCount(), "Flag count changed");
        assertEquals("Target", parsed.getVictimName(), "Victim changed");
        assertEquals("world x=1.0 y=64.0 z=-2.0", parsed.getLocation(), "Location changed");

        String legacy = "[" + source.getFormattedTimestamp() + "] "
                + "PLAYER=TestPlayer       UUID=" + uuid
                + " CHECK=PacketOrder               FLAGS=3    VALUE=1.2500 "
                + "VICTIM=Target           LOCATION=world x=1.0 y=64.0 z=-2.0";
        FlagLogEntry parsedLegacy = FlagLogEntry.fromLogLine(legacy, ignored -> null);
        assertNotNull(parsedLegacy, "Legacy flags.log line did not parse");
        assertEquals(uuid, parsedLegacy.getPlayerUUID(), "Legacy UUID migration failed");

        System.out.println("FlagLogEntry regression simulation passed.");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        assertTrue(!value, message);
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }
}
