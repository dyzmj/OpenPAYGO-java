package com.goldcard.paygo.metrics;

public enum AuthMethod {
    SIMPLE_AUTH("sa"),
    TIMESTAMP_AUTH("ta"),
    COUNTER_AUTH("ca"),
    DATA_AUTH("da"),
    RECURSIVE_DATA_AUTH("ra");

    private final String code;

    AuthMethod(String code) { this.code = code; }

    public String getCode() { return code; }

    public static AuthMethod fromCode(String code) {
        for (AuthMethod method : values()) {
            if (method.code.equals(code)) return method;
        }
        throw new IllegalArgumentException("Unknown authentication method: " + code);
    }
}
