package com.goldcard.paygo.token;

import java.math.BigDecimal;
import java.util.Optional;

public final class TokenDecodeResult {
    private final TokenStatus status;
    private final TokenType tokenType;
    private final BigDecimal activationValue;
    private final Long rawValue;
    private final TokenState updatedState;

    private TokenDecodeResult(TokenStatus status, TokenType tokenType,
                              BigDecimal activationValue, Long rawValue,
                              TokenState updatedState) {
        this.status = status;
        this.tokenType = tokenType;
        this.activationValue = activationValue;
        this.rawValue = rawValue;
        this.updatedState = updatedState;
    }

    public static TokenDecodeResult valid(TokenType type, BigDecimal activationValue,
                                          long rawValue, TokenState state) {
        if (type == null || state == null) throw new NullPointerException("type and state are required");
        return new TokenDecodeResult(TokenStatus.VALID, type, activationValue,
                Long.valueOf(rawValue), state);
    }

    public static TokenDecodeResult invalid() {
        return new TokenDecodeResult(TokenStatus.INVALID, null, null, null, null);
    }

    public static TokenDecodeResult alreadyUsed() {
        return new TokenDecodeResult(TokenStatus.ALREADY_USED, null, null, null, null);
    }

    public TokenStatus getStatus() { return status; }
    public Optional<TokenType> getTokenType() { return Optional.ofNullable(tokenType); }
    public Optional<BigDecimal> getActivationValue() { return Optional.ofNullable(activationValue); }
    public Optional<Long> getRawValue() { return Optional.ofNullable(rawValue); }
    public Optional<TokenState> getUpdatedState() { return Optional.ofNullable(updatedState); }
}
