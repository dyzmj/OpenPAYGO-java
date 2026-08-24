package com.goldcard.paygo.token;

import java.util.Objects;

public final class TokenGenerationResult {
    private final long updatedTokenCount;
    private final String token;

    public TokenGenerationResult(long updatedTokenCount, String token) {
        this.updatedTokenCount = updatedTokenCount;
        this.token = Objects.requireNonNull(token, "token");
    }

    public long getUpdatedTokenCount() { return updatedTokenCount; }
    public String getToken() { return token; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TokenGenerationResult)) return false;
        TokenGenerationResult that = (TokenGenerationResult) other;
        return updatedTokenCount == that.updatedTokenCount && token.equals(that.token);
    }

    @Override
    public int hashCode() { return Objects.hash(updatedTokenCount, token); }

    @Override
    public String toString() {
        return "TokenGenerationResult{updatedTokenCount=" + updatedTokenCount
                + ", token='" + token + "'}";
    }
}
