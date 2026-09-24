package com.antielytratarget.utils;

import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.geyser.api.GeyserApi;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BedrockPlayerUtilRegressionSimulation {

    private BedrockPlayerUtilRegressionSimulation() {
    }

    public static void main(String[] args) {
        UUID floodgate = UUID.fromString("00000000-0000-0000-0009-000000000001");
        UUID javaPlayer = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        UUID apiDetected = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");
        UUID delayedApiDetected = UUID.fromString("123e4567-e89b-42d3-a456-426614174002");
        UUID geyserApiDetected = UUID.fromString("123e4567-e89b-42d3-a456-426614174003");
        UUID similarButInvalid = UUID.fromString("00000000-0000-4000-0009-000000000001");

        if (!BedrockPlayerUtil.isFloodgateUuid(floodgate)
                || !BedrockPlayerUtil.isBedrockPlayer(floodgate)) {
            throw new AssertionError("Floodgate UUID was not recognized as Bedrock");
        }
        if (BedrockPlayerUtil.isFloodgateUuid(javaPlayer)
                || BedrockPlayerUtil.isBedrockPlayer(javaPlayer)) {
            throw new AssertionError("Java UUID was incorrectly recognized as Bedrock");
        }
        if (BedrockPlayerUtil.isFloodgateUuid(apiDetected)
                || !BedrockPlayerUtil.isBedrockPlayer(apiDetected)) {
            throw new AssertionError("Floodgate API player was not recognized as Bedrock");
        }
        if (BedrockPlayerUtil.isFloodgateUuid(similarButInvalid)) {
            throw new AssertionError("Loose Floodgate UUID matching caused a false positive");
        }
        if (BedrockPlayerUtil.isBedrockPlayer(null)) {
            throw new AssertionError("Null UUID was incorrectly recognized as Bedrock");
        }
        if (!BedrockPlayerUtil.hasConfiguredNamePrefix(".xKEREM19886", ".")) {
            throw new AssertionError("Velocity/Geyser name prefix was not recognized");
        }
        if (BedrockPlayerUtil.hasConfiguredNamePrefix("xKEREM19886", ".")
                || BedrockPlayerUtil.hasConfiguredNamePrefix(".xKEREM19886", "")
                || BedrockPlayerUtil.hasConfiguredNamePrefix(null, ".")) {
            throw new AssertionError("Bedrock name-prefix fallback was too permissive");
        }

        if (BedrockPlayerUtil.isBedrockPlayer(delayedApiDetected)) {
            throw new AssertionError("Unregistered Floodgate player was detected too early");
        }
        FloodgateApi.setFloodgatePlayer(delayedApiDetected, true);
        if (!BedrockPlayerUtil.isBedrockPlayer(delayedApiDetected)) {
            throw new AssertionError("Late Floodgate API registration was not recognized");
        }

        if (BedrockPlayerUtil.isBedrockPlayer(geyserApiDetected)) {
            throw new AssertionError("Unregistered Geyser player was detected too early");
        }
        GeyserApi.setBedrockPlayer(geyserApiDetected, true);
        if (!BedrockPlayerUtil.isBedrockPlayer(geyserApiDetected)) {
            throw new AssertionError("Geyser API player was not recognized as Bedrock");
        }

        verifyNegativeResultIsRetried(javaPlayer);

        System.out.println("Bedrock player detection regression simulations passed.");
    }

    private static void verifyNegativeResultIsRetried(UUID uuid) {
        AtomicBoolean apiResult = new AtomicBoolean(false);
        long created = 1_000_000_000L;
        BedrockPlayerResolver resolver = new BedrockPlayerResolver(
                uuid, ignored -> apiResult.get(), created);

        if (resolver.resolve(created)) {
            throw new AssertionError("Resolver incorrectly promoted an initial Java result");
        }

        apiResult.set(true);
        if (resolver.resolve(created + BedrockPlayerResolver.FAST_RETRY_INTERVAL_NANOS - 1L)) {
            throw new AssertionError("Resolver ignored its bounded retry interval");
        }
        if (!resolver.resolve(created + BedrockPlayerResolver.FAST_RETRY_INTERVAL_NANOS)) {
            throw new AssertionError("Resolver permanently cached an early negative result");
        }

        apiResult.set(false);
        if (!resolver.resolve(created + BedrockPlayerResolver.FAST_RETRY_INTERVAL_NANOS * 2L)) {
            throw new AssertionError("Confirmed Bedrock status must remain one-way and stable");
        }
    }
}
