package com.goldcard.paygo.token;

/**
 * Immutable security policy controlling accepted token-count windows.
 *
 * <p>Defaults limit ordinary forward jumps to 64 counts, unordered Add Time history to 16 counts,
 * and Counter Sync movement to 64 counts backward or 100 counts forward. Counter reset to zero is
 * disabled by default because it invalidates normal replay assumptions.</p>
 *
 * @author dyzmj
 */
public final class TokenValidationPolicy {
    public static final int DEFAULT_NORMAL_FORWARD_JUMP = 64;
    public static final int DEFAULT_UNORDERED_BACKWARD_WINDOW = 16;
    public static final int DEFAULT_COUNTER_SYNC_BACKWARD_WINDOW = 64;
    public static final int DEFAULT_COUNTER_SYNC_FORWARD_JUMP = 100;

    private final int normalForwardJump;
    private final int unorderedBackwardWindow;
    private final int counterSyncBackwardWindow;
    private final int counterSyncForwardJump;
    private final boolean counterResetEnabled;

    private TokenValidationPolicy(Builder builder) {
        this.normalForwardJump = positive(builder.normalForwardJump, "normalForwardJump");
        this.unorderedBackwardWindow = nonNegative(builder.unorderedBackwardWindow,
                "unorderedBackwardWindow");
        this.counterSyncBackwardWindow = nonNegative(builder.counterSyncBackwardWindow,
                "counterSyncBackwardWindow");
        this.counterSyncForwardJump = positive(builder.counterSyncForwardJump,
                "counterSyncForwardJump");
        this.counterResetEnabled = builder.counterResetEnabled;
    }

    public static TokenValidationPolicy defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    public int getNormalForwardJump() { return normalForwardJump; }
    public int getUnorderedBackwardWindow() { return unorderedBackwardWindow; }
    public int getCounterSyncBackwardWindow() { return counterSyncBackwardWindow; }
    public int getCounterSyncForwardJump() { return counterSyncForwardJump; }
    public boolean isCounterResetEnabled() { return counterResetEnabled; }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    public static final class Builder {
        private int normalForwardJump = DEFAULT_NORMAL_FORWARD_JUMP;
        private int unorderedBackwardWindow = DEFAULT_UNORDERED_BACKWARD_WINDOW;
        private int counterSyncBackwardWindow = DEFAULT_COUNTER_SYNC_BACKWARD_WINDOW;
        private int counterSyncForwardJump = DEFAULT_COUNTER_SYNC_FORWARD_JUMP;
        private boolean counterResetEnabled;

        private Builder() {}

        public Builder normalForwardJump(int value) { this.normalForwardJump = value; return this; }
        public Builder unorderedBackwardWindow(int value) { this.unorderedBackwardWindow = value; return this; }
        public Builder counterSyncBackwardWindow(int value) { this.counterSyncBackwardWindow = value; return this; }
        public Builder counterSyncForwardJump(int value) { this.counterSyncForwardJump = value; return this; }
        public Builder counterResetEnabled(boolean value) { this.counterResetEnabled = value; return this; }
        public TokenValidationPolicy build() { return new TokenValidationPolicy(this); }
    }
}
