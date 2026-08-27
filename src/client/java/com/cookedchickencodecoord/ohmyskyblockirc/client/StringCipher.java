package com.cookedchickencodecoord.ohmyskyblockirc.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Exact implementation of NolsticeIrcServer.Core.StringCipher. */
final class StringCipher {
    private StringCipher() {}

    static String encode(String input) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            byte tmp = bytes[i];
            bytes[i] = bytes[i + 1];
            bytes[i + 1] = tmp;
        }
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ~bytes[i];
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    static String decode(String input) {
        byte[] bytes = Base64.getDecoder().decode(input);
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ~bytes[i];
        }
        for (int i = 0; i + 1 < bytes.length; i += 2) {
            byte tmp = bytes[i];
            bytes[i] = bytes[i + 1];
            bytes[i + 1] = tmp;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String encrypt(String plaintext, String key) {
        byte[] keyBytes = fixedKey(key);
        byte[] plain = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipher = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            cipher[i] = (byte) (plain[i] ^ keyBytes[i % 16]);
        }
        return Base64.getEncoder().encodeToString(cipher);
    }

    static String decrypt(String ciphertext, String key) {
        byte[] keyBytes = fixedKey(key);
        byte[] cipher = Base64.getDecoder().decode(ciphertext);
        byte[] plain = new byte[cipher.length];
        for (int i = 0; i < cipher.length; i++) {
            plain[i] = (byte) (cipher[i] ^ keyBytes[i % 16]);
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static byte[] fixedKey(String key) {
        byte[] result = new byte[16];
        byte[] raw = (key == null ? "" : key).getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, result, 0, Math.min(raw.length, result.length));
        return result;
    }
}
