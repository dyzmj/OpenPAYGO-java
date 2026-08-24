package com.goldcard.paygo.internal;

final class HexKeys {
    private HexKeys() {}

    static byte[] decodeSecretKey(String secretKey) {
        if (secretKey == null || !secretKey.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException(
                    "secretKey must contain exactly 32 hexadecimal characters");
        }
        byte[] result = new byte[16];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(secretKey.charAt(i * 2), 16);
            int low = Character.digit(secretKey.charAt(i * 2 + 1), 16);
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
