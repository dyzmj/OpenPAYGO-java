package com.goldcard.paygo.internal;

/**
 * Allocation-light SipHash-2-4 implementation used by both OpenPAYGO protocols.
 *
 * @author dyzmj
 */
final class SipHash24 {
    private SipHash24() {}

    /**
     * Computes the SipHash-2-4 message authentication code using the reference little-endian block
     * layout, two compression rounds per block, and four finalization rounds.
     *
     * @param key exactly 16 key bytes
     * @param message arbitrary message bytes
     * @return the raw unsigned 64-bit result stored in a Java {@code long}
     */
    static long hash(byte[] key, byte[] message) {
        if (key == null || key.length != 16) {
            throw new IllegalArgumentException("SipHash key must contain exactly 16 bytes");
        }
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }

        long k0 = readLittleEndian(key, 0);
        long k1 = readLittleEndian(key, 8);
        long[] state = {
            0x736f6d6570736575L ^ k0,
            0x646f72616e646f6dL ^ k1,
            0x6c7967656e657261L ^ k0,
            0x7465646279746573L ^ k1
        };

        int offset = 0;
        while (offset + 8 <= message.length) {
            long word = readLittleEndian(message, offset);
            state[3] ^= word;
            sipRounds(state, 2);
            state[0] ^= word;
            offset += 8;
        }

        long tail = ((long) message.length) << 56;
        for (int i = 0; offset + i < message.length; i++) {
            tail |= ((long) message[offset + i] & 0xffL) << (8 * i);
        }
        state[3] ^= tail;
        sipRounds(state, 2);
        state[0] ^= tail;
        state[2] ^= 0xffL;
        sipRounds(state, 4);
        return state[0] ^ state[1] ^ state[2] ^ state[3];
    }

    private static long readLittleEndian(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value |= ((long) bytes[offset + i] & 0xffL) << (8 * i);
        }
        return value;
    }

    private static void sipRounds(long[] v, int count) {
        for (int i = 0; i < count; i++) {
            v[0] += v[1];
            v[1] = Long.rotateLeft(v[1], 13);
            v[1] ^= v[0];
            v[0] = Long.rotateLeft(v[0], 32);
            v[2] += v[3];
            v[3] = Long.rotateLeft(v[3], 16);
            v[3] ^= v[2];
            v[0] += v[3];
            v[3] = Long.rotateLeft(v[3], 21);
            v[3] ^= v[0];
            v[2] += v[1];
            v[1] = Long.rotateLeft(v[1], 17);
            v[1] ^= v[2];
            v[2] = Long.rotateLeft(v[2], 32);
        }
    }
}
