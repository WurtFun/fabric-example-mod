package com.cookedchickencodecoord.ohmyskyblockirc.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class InstallIdStore {

    private static final String PREFIX = "OhMySkyblockIRC_v1:";

    private InstallIdStore() {
    }

    public static String getHwid(UUID minecraftUuid) {
        return sha256(PREFIX + minecraftUuid);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash =
                    digest.digest(value.getBytes(StandardCharsets.UTF_8));

            StringBuilder result =
                    new StringBuilder(hash.length * 2);

            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    e
            );
        }
    }
}
