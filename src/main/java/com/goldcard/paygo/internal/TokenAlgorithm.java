package com.goldcard.paygo.internal;

import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenType;
import com.goldcard.paygo.token.TokenValidationPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Implements OpenPAYGO Token v2.3 chain generation, decoding, and replay-state transitions.
 *
 * @author dyzmj
 */
public final class TokenAlgorithm {
    private static final long STANDARD_OFFSET = 1_000L;
    private static final long STANDARD_MAX_TOKEN = 999_999_999L;
    private static final long STANDARD_MAX_VALUE = 995L;
    private static final long DISABLE_PAYG_VALUE = 998L;
    private static final long COUNTER_SYNC_VALUE = 999L;
    private static final long EXTENDED_OFFSET = 1_000_000L;
    private static final long EXTENDED_MAX_TOKEN = 999_999_999_999L;
    private static final long EXTENDED_MAX_VALUE = 999_999L;

    private TokenAlgorithm() {}

    /**
     * Encodes a command into either the standard 30-bit chain or extended 40-bit chain.
     * The value-bearing base remains stable while the upper token digits advance through SipHash.
     */
    public static TokenGenerationResult generate(TokenGenerationRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        byte[] key = HexKeys.decodeSecretKey(request.getSecretKey());
        long startingCode = resolveStartingCode(request.getStartingCode(), key);
        long rawValue = rawValue(request);
        long updatedCount = nextCount(request.getCurrentTokenCount(), request.getTokenType());
        long token = request.isExtendedToken()
                ? generateExtended(startingCode, rawValue, updatedCount, key)
                : generateStandard(startingCode, rawValue, updatedCount, key);
        String formatted = request.isRestrictedDigitSet()
                ? restrictedDigits(token, request.isExtendedToken() ? 40 : 30)
                : leftPad(token, request.isExtendedToken() ? 12 : 9);
        return new TokenGenerationResult(updatedCount, formatted);
    }

    /**
     * Searches the configured forward window for a chain match, then applies replay and unordered
     * entry policy before returning an updated immutable token state.
     */
    public static TokenDecodeResult decode(TokenDecodeRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        byte[] key = HexKeys.decodeSecretKey(request.getSecretKey());
        boolean extended = validateAndDetectFormat(request.getToken(),
                request.isRestrictedDigitSet());
        long inputToken = request.isRestrictedDigitSet()
                ? fromRestrictedDigits(request.getToken())
                : Long.parseLong(request.getToken());
        long startingCode = resolveStartingCode(request.getStartingCode(), key);
        long offset = extended ? EXTENDED_OFFSET : STANDARD_OFFSET;
        long tokenBase = inputToken % offset;
        long rawValue = decodeBase(startingCode % offset, tokenBase, offset);
        long currentCode = putBase(startingCode, tokenBase, offset);
        TokenState state = request.getTokenState();
        TokenValidationPolicy policy = request.getValidationPolicy();
        long maxForward = !extended && rawValue == COUNTER_SYNC_VALUE
                ? policy.getCounterSyncForwardJump() : policy.getNormalForwardJump();
        long maxCount = safeAdd(state.getCurrentTokenCount(), maxForward);
        boolean matchedButRejected = false;

        for (long count = 0; count <= maxCount; count++) {
            if (putBase(currentCode, tokenBase, offset) == inputToken) {
                TokenType type = typeFor(count, rawValue, extended);
                if (isCountValid(count, state, type, rawValue, extended, policy)) {
                    TokenState updated = updateState(state, count, type, rawValue, policy);
                    BigDecimal activation = type == TokenType.ADD_TIME || type == TokenType.SET_TIME
                            ? BigDecimal.valueOf(rawValue)
                                .divide(BigDecimal.valueOf(request.getValueDivider()),
                                        16, RoundingMode.HALF_EVEN)
                                .stripTrailingZeros()
                            : null;
                    return TokenDecodeResult.valid(type, activation, rawValue, updated);
                }
                matchedButRejected = true;
            }
            if (count < maxCount) {
                currentCode = extended
                        ? nextExtendedToken(currentCode, key)
                        : nextStandardToken(currentCode, key);
            }
        }
        return matchedButRejected ? TokenDecodeResult.alreadyUsed() : TokenDecodeResult.invalid();
    }

    private static long rawValue(TokenGenerationRequest request) {
        TokenType type = request.getTokenType();
        if (type == TokenType.DISABLE_PAYG) return DISABLE_PAYG_VALUE;
        if (type == TokenType.COUNTER_SYNC) return COUNTER_SYNC_VALUE;
        BigDecimal value = request.getActivationValue();
        if (value.signum() < 0) {
            throw new IllegalArgumentException("activationValue must be non-negative");
        }
        long raw;
        try {
            raw = value.multiply(BigDecimal.valueOf(request.getValueDivider()))
                    .setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("activationValue is outside the supported range", exception);
        }
        long maximum = request.isExtendedToken() ? EXTENDED_MAX_VALUE : STANDARD_MAX_VALUE;
        if (raw > maximum) {
            throw new IllegalArgumentException("activationValue is too high");
        }
        return raw;
    }

    private static long nextCount(long current, TokenType type) {
        boolean needsOdd = type != TokenType.ADD_TIME;
        long increment = ((current & 1L) == (needsOdd ? 1L : 0L)) ? 2L : 1L;
        return safeAdd(current, increment);
    }

    private static long generateStandard(long startingCode, long value, long count, byte[] key) {
        long base = encodeBase(startingCode % STANDARD_OFFSET, value, STANDARD_OFFSET);
        long current = putBase(startingCode, base, STANDARD_OFFSET);
        for (long i = 0; i < count; i++) current = nextStandardToken(current, key);
        return putBase(current, base, STANDARD_OFFSET);
    }

    private static long generateExtended(long startingCode, long value, long count, byte[] key) {
        long base = encodeBase(startingCode % EXTENDED_OFFSET, value, EXTENDED_OFFSET);
        long current = putBase(startingCode, base, EXTENDED_OFFSET);
        for (long i = 0; i < count; i++) current = nextExtendedToken(current, key);
        return putBase(current, base, EXTENDED_OFFSET);
    }

    private static long resolveStartingCode(Long supplied, byte[] key) {
        long code = supplied == null ? startingCode(key) : supplied.longValue();
        if (code < 0 || code > STANDARD_MAX_TOKEN) {
            throw new IllegalArgumentException("startingCode must fit in a 9-digit unsigned value");
        }
        return code;
    }

    private static long startingCode(byte[] key) {
        return standardHashToToken(SipHash24.hash(key, key));
    }

    private static long nextStandardToken(long token, byte[] key) {
        if (token < 0 || token > 0xffff_ffffL) {
            throw new IllegalArgumentException("standard token does not fit in 32 bits");
        }
        byte[] four = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .putInt((int) token).array();
        byte[] message = new byte[8];
        System.arraycopy(four, 0, message, 0, 4);
        System.arraycopy(four, 0, message, 4, 4);
        return standardHashToToken(SipHash24.hash(key, message));
    }

    private static long standardHashToToken(long hash) {
        int folded = (int) (hash >>> 32) ^ (int) hash;
        long token = Integer.toUnsignedLong(folded) >>> 2;
        return token > STANDARD_MAX_TOKEN ? token - 73_741_825L : token;
    }

    private static long nextExtendedToken(long token, byte[] key) {
        byte[] message = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putLong(token).array();
        long value = SipHash24.hash(key, message) >>> 24;
        return value > EXTENDED_MAX_TOKEN ? value - 99_511_627_777L : value;
    }

    private static long encodeBase(long base, long value, long offset) {
        return (base + value) % offset;
    }

    private static long decodeBase(long startingBase, long tokenBase, long offset) {
        long value = tokenBase - startingBase;
        return value < 0 ? value + offset : value;
    }

    private static long putBase(long token, long base, long offset) {
        if (base < 0 || base >= offset) throw new IllegalArgumentException("invalid token base");
        return token - (token % offset) + base;
    }

    private static String restrictedDigits(long value, int bits) {
        StringBuilder result = new StringBuilder(bits / 2);
        for (int shift = bits - 2; shift >= 0; shift -= 2) {
            result.append((char) ('1' + ((value >>> shift) & 3L)));
        }
        return result.toString();
    }

    private static long fromRestrictedDigits(String token) {
        long value = 0L;
        for (int i = 0; i < token.length(); i++) {
            value = (value << 2) | (token.charAt(i) - '1');
        }
        return value;
    }

    private static String leftPad(long value, int width) {
        String number = Long.toString(value);
        StringBuilder result = new StringBuilder(width);
        for (int i = number.length(); i < width; i++) result.append('0');
        return result.append(number).toString();
    }

    private static boolean validateAndDetectFormat(String token, boolean restricted) {
        if (restricted) {
            if (!token.matches("[1-4]+")) {
                throw new IllegalArgumentException("restricted token must contain only digits 1 through 4");
            }
            if (token.length() == 15) return false;
            if (token.length() == 20) return true;
            throw new IllegalArgumentException("restricted token must contain 15 or 20 digits");
        }
        if (!token.matches("[0-9]+")) {
            throw new IllegalArgumentException("token must contain only decimal digits");
        }
        if (token.length() == 9) return false;
        if (token.length() == 12) return true;
        throw new IllegalArgumentException("token must contain 9 or 12 digits");
    }

    private static TokenType typeFor(long count, long rawValue, boolean extended) {
        if (!extended && rawValue == COUNTER_SYNC_VALUE) return TokenType.COUNTER_SYNC;
        if (!extended && rawValue == DISABLE_PAYG_VALUE) return TokenType.DISABLE_PAYG;
        return (count & 1L) == 0L ? TokenType.ADD_TIME : TokenType.SET_TIME;
    }

    private static boolean isCountValid(long count, TokenState state, TokenType type,
                                        long rawValue, boolean extended,
                                        TokenValidationPolicy policy) {
        // Counter Sync has a separate bidirectional window because its purpose is to repair count
        // drift. Count zero is an explicit reset operation and remains disabled unless opted in.
        long current = state.getCurrentTokenCount();
        if (!extended && rawValue == COUNTER_SYNC_VALUE) {
            if (count == 0) return policy.isCounterResetEnabled();
            long lower = Math.max(0L, current - policy.getCounterSyncBackwardWindow());
            return count >= lower && count <= safeAdd(current, policy.getCounterSyncForwardJump());
        }
        if (count > current) {
            return count <= safeAdd(current, policy.getNormalForwardJump());
        }
        return state.isUnorderedEntryEnabled()
                && type == TokenType.ADD_TIME
                && count > Math.max(-1L, current - policy.getUnorderedBackwardWindow())
                && !state.getUsedTokenCounts().contains(Long.valueOf(count));
    }

    private static TokenState updateState(TokenState state, long count, TokenType type,
                                          long rawValue, TokenValidationPolicy policy) {
        // Non-Add commands consume the entire retained backward window. This prevents an older
        // Add Time token from re-enabling or extending service after Set, Disable, or Counter Sync.
        boolean counterSync = rawValue == COUNTER_SYNC_VALUE && type == TokenType.COUNTER_SYNC;
        long newCurrent = counterSync ? count : Math.max(state.getCurrentTokenCount(), count);
        if (!state.isUnorderedEntryEnabled()) return TokenState.strict(newCurrent);

        long bottom = Math.max(0L, newCurrent - policy.getUnorderedBackwardWindow());
        Set<Long> used = new LinkedHashSet<Long>();
        if (type != TokenType.ADD_TIME || counterSync) {
            for (long candidate = bottom; candidate <= newCurrent; candidate++) {
                used.add(Long.valueOf(candidate));
            }
        } else {
            for (Long old : state.getUsedTokenCounts()) {
                if (old.longValue() >= bottom && old.longValue() <= newCurrent) used.add(old);
            }
            used.add(Long.valueOf(count));
        }
        return TokenState.unordered(newCurrent, used);
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            throw new IllegalArgumentException("token count is too high");
        }
        return left + right;
    }
}
