package com.antielytratarget.utils;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;

public final class ClientVersionExemptions {

    private ClientVersionExemptions() {
    }

    public static boolean isPacketOrderEExempt(ClientVersion version) {
        return version == ClientVersion.V_1_21_7;
    }

    public static boolean isUseItemRotationMismatchExempt(ClientVersion version) {
        return version == ClientVersion.V_1_16_4;
    }
}
