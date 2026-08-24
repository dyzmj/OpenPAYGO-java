package com.goldcard.paygo.metrics;

public final class MetricsAuthPolicy {
    private final boolean allowSimpleAuth;

    private MetricsAuthPolicy(Builder builder) {
        this.allowSimpleAuth = builder.allowSimpleAuth;
    }

    public static MetricsAuthPolicy secureDefaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public boolean isSimpleAuthAllowed() { return allowSimpleAuth; }

    public static final class Builder {
        private boolean allowSimpleAuth;
        private Builder() {}
        public Builder allowSimpleAuth(boolean value) { this.allowSimpleAuth = value; return this; }
        public MetricsAuthPolicy build() { return new MetricsAuthPolicy(this); }
    }
}
