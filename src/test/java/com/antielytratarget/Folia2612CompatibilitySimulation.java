package com.antielytratarget;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Folia2612CompatibilitySimulation {

    private Folia2612CompatibilitySimulation() {}

    public static void main(String[] args) throws Exception {
        require(ServerVersion.valueOf("V_26_1_2")
                .isNewerThan(ServerVersion.V_1_21_11));

        require(ClientVersion.valueOf("V_26_1")
                .isNewerThan(ClientVersion.V_1_21_11));

        try (InputStream stream = Folia2612CompatibilitySimulation.class
                .getResourceAsStream("/plugin.yml")) {
            require(stream != null);
            String descriptor = new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8);
            require(descriptor.contains("folia-supported: true"));
        }

        System.out.println("Folia 26.1.2 compatibility simulation passed.");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Folia 26.1.2 compatibility check failed");
        }
    }
}
