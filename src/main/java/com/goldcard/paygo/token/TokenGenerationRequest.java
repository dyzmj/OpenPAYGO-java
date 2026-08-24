package com.goldcard.paygo.token;

import java.math.BigDecimal;
import java.util.Objects;

public final class TokenGenerationRequest {
    private final String secretKey;
    private final long currentTokenCount;
    private final TokenType tokenType;
    private final BigDecimal activationValue;
    private final int valueDivider;
    private final Long startingCode;
    private final boolean restrictedDigitSet;
    private final boolean extendedToken;

    private TokenGenerationRequest(Builder builder) {
        this.secretKey = requireText(builder.secretKey, "secretKey");
        if (builder.currentTokenCount < 0) {
            throw new IllegalArgumentException("currentTokenCount must be non-negative");
        }
        this.currentTokenCount = builder.currentTokenCount;
        this.tokenType = Objects.requireNonNull(builder.tokenType, "tokenType");
        this.activationValue = builder.activationValue;
        if ((tokenType == TokenType.ADD_TIME || tokenType == TokenType.SET_TIME)
                && activationValue == null) {
            throw new IllegalArgumentException("activationValue is required for Add Time and Set Time");
        }
        if ((tokenType == TokenType.DISABLE_PAYG || tokenType == TokenType.COUNTER_SYNC)
                && activationValue != null) {
            throw new IllegalArgumentException("activationValue is not allowed for this token type");
        }
        if (builder.valueDivider < 1 || builder.valueDivider > 255) {
            throw new IllegalArgumentException("valueDivider must be between 1 and 255");
        }
        this.valueDivider = builder.valueDivider;
        if (builder.startingCode != null && builder.startingCode.longValue() < 0) {
            throw new IllegalArgumentException("startingCode must be non-negative");
        }
        this.startingCode = builder.startingCode;
        this.restrictedDigitSet = builder.restrictedDigitSet;
        this.extendedToken = builder.extendedToken;
        if (extendedToken
                && (tokenType == TokenType.DISABLE_PAYG || tokenType == TokenType.COUNTER_SYNC)) {
            throw new IllegalArgumentException("extended tokens support only Add Time and Set Time");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSecretKey() { return secretKey; }
    public long getCurrentTokenCount() { return currentTokenCount; }
    public TokenType getTokenType() { return tokenType; }
    public BigDecimal getActivationValue() { return activationValue; }
    public int getValueDivider() { return valueDivider; }
    public Long getStartingCode() { return startingCode; }
    public boolean isRestrictedDigitSet() { return restrictedDigitSet; }
    public boolean isExtendedToken() { return extendedToken; }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    public static final class Builder {
        private String secretKey;
        private long currentTokenCount;
        private TokenType tokenType = TokenType.ADD_TIME;
        private BigDecimal activationValue;
        private int valueDivider = 1;
        private Long startingCode;
        private boolean restrictedDigitSet;
        private boolean extendedToken;

        private Builder() {}

        public Builder secretKey(String value) { this.secretKey = value; return this; }
        public Builder currentTokenCount(long value) { this.currentTokenCount = value; return this; }
        public Builder tokenType(TokenType value) { this.tokenType = value; return this; }
        public Builder activationValue(BigDecimal value) { this.activationValue = value; return this; }
        public Builder valueDivider(int value) { this.valueDivider = value; return this; }
        public Builder startingCode(Long value) { this.startingCode = value; return this; }
        public Builder restrictedDigitSet(boolean value) { this.restrictedDigitSet = value; return this; }
        public Builder extendedToken(boolean value) { this.extendedToken = value; return this; }
        public TokenGenerationRequest build() { return new TokenGenerationRequest(this); }
    }
}
