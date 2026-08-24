package com.goldcard.paygo.metrics;

import java.util.Optional;

public final class AuthValidationResult {
    private final boolean valid;
    private final AuthMethod authMethod;
    private final AuthValidationReason reason;

    private AuthValidationResult(boolean valid, AuthMethod authMethod,
                                 AuthValidationReason reason) {
        this.valid = valid;
        this.authMethod = authMethod;
        this.reason = reason;
    }

    public static AuthValidationResult valid(AuthMethod method) {
        return new AuthValidationResult(true, method, AuthValidationReason.VALID);
    }

    public static AuthValidationResult invalid(AuthMethod method,
                                               AuthValidationReason reason) {
        if (reason == AuthValidationReason.VALID) {
            throw new IllegalArgumentException("invalid result requires a failure reason");
        }
        return new AuthValidationResult(false, method, reason);
    }

    public boolean isValid() { return valid; }
    public Optional<AuthMethod> getAuthMethod() { return Optional.ofNullable(authMethod); }
    public AuthValidationReason getReason() { return reason; }
}
