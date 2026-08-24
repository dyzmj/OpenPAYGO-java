package com.goldcard.paygo.metrics;

public final class MetricsDeviceParameters {
    private final String secretKey;
    private final MetricsDataFormat dataFormat;
    private final Long lastRequestCount;
    private final Long lastRequestTimestamp;
    private final MetricsAuthPolicy authPolicy;

    private MetricsDeviceParameters(Builder builder) {
        this.secretKey = validSecretKey(builder.secretKey);
        this.dataFormat = builder.dataFormat;
        this.lastRequestCount = nonNegative(builder.lastRequestCount, "lastRequestCount");
        this.lastRequestTimestamp = nonNegative(builder.lastRequestTimestamp,
                "lastRequestTimestamp");
        this.authPolicy = builder.authPolicy == null
                ? MetricsAuthPolicy.secureDefaults() : builder.authPolicy;
    }

    public static Builder builder() { return new Builder(); }
    public String getSecretKey() { return secretKey; }
    public MetricsDataFormat getDataFormat() { return dataFormat; }
    public Long getLastRequestCount() { return lastRequestCount; }
    public Long getLastRequestTimestamp() { return lastRequestTimestamp; }
    public MetricsAuthPolicy getAuthPolicy() { return authPolicy; }

    private static Long nonNegative(Long value, String name) {
        if (value != null && value.longValue() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static String validSecretKey(String value) {
        if (value != null && !value.matches("(?i)[0-9a-f]{32}")) {
            throw new IllegalArgumentException("secretKey must contain exactly 32 hex characters");
        }
        return value;
    }

    public static final class Builder {
        private String secretKey;
        private MetricsDataFormat dataFormat;
        private Long lastRequestCount;
        private Long lastRequestTimestamp;
        private MetricsAuthPolicy authPolicy;
        private Builder() {}
        public Builder secretKey(String value) { this.secretKey = value; return this; }
        public Builder dataFormat(MetricsDataFormat value) { this.dataFormat = value; return this; }
        public Builder lastRequestCount(Long value) { this.lastRequestCount = value; return this; }
        public Builder lastRequestTimestamp(Long value) { this.lastRequestTimestamp = value; return this; }
        public Builder authPolicy(MetricsAuthPolicy value) { this.authPolicy = value; return this; }
        public MetricsDeviceParameters build() { return new MetricsDeviceParameters(this); }
    }
}
