package com.goldcard.paygo.token;

import java.util.Objects;

public final class TokenDecodeRequest {
    private final String token;
    private final String secretKey;
    private final TokenState tokenState;
    private final Long startingCode;
    private final int valueDivider;
    private final boolean restrictedDigitSet;
    private final TokenValidationPolicy validationPolicy;

    private TokenDecodeRequest(Builder builder) {
        this.token = requireText(builder.token, "token");
        this.secretKey = requireText(builder.secretKey, "secretKey");
        this.tokenState = Objects.requireNonNull(builder.tokenState, "tokenState");
        if (builder.startingCode != null && builder.startingCode < 0) {
            throw new IllegalArgumentException("startingCode must be non-negative");
        }
        this.startingCode = builder.startingCode;
        if (builder.valueDivider < 1 || builder.valueDivider > 255) {
            throw new IllegalArgumentException("valueDivider must be between 1 and 255");
        }
        this.valueDivider = builder.valueDivider;
        this.restrictedDigitSet = builder.restrictedDigitSet;
        this.validationPolicy = builder.validationPolicy == null
                ? TokenValidationPolicy.defaults() : builder.validationPolicy;
    }

    public static Builder builder() { return new Builder(); }
    public String getToken() { return token; }
    public String getSecretKey() { return secretKey; }
    public TokenState getTokenState() { return tokenState; }
    public Long getStartingCode() { return startingCode; }
    public int getValueDivider() { return valueDivider; }
    public boolean isRestrictedDigitSet() { return restrictedDigitSet; }
    public TokenValidationPolicy getValidationPolicy() { return validationPolicy; }

    private static String requireText(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static final class Builder {
        private String token;
        private String secretKey;
        private TokenState tokenState;
        private Long startingCode;
        private int valueDivider = 1;
        private boolean restrictedDigitSet;
        private TokenValidationPolicy validationPolicy;

        private Builder() {}

        public Builder token(String value) { this.token = value; return this; }
        public Builder secretKey(String value) { this.secretKey = value; return this; }
        public Builder tokenState(TokenState value) { this.tokenState = value; return this; }
        public Builder startingCode(Long value) { this.startingCode = value; return this; }
        public Builder valueDivider(int value) { this.valueDivider = value; return this; }
        public Builder restrictedDigitSet(boolean value) { this.restrictedDigitSet = value; return this; }
        public Builder validationPolicy(TokenValidationPolicy value) { this.validationPolicy = value; return this; }
        public TokenDecodeRequest build() { return new TokenDecodeRequest(this); }
    }
}
