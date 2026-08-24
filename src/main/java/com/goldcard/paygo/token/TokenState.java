package com.goldcard.paygo.token;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable device-side replay state used while decoding tokens.
 *
 * <p>Strict state accepts only newer counts. Unordered state additionally records accepted Add
 * Time counts so unused tokens inside the configured backward window may be entered out of order.</p>
 *
 * @author dyzmj
 */
public final class TokenState {
    private final long currentTokenCount;
    private final boolean unorderedEntryEnabled;
    private final Set<Long> usedTokenCounts;

    private TokenState(long currentTokenCount, boolean unorderedEntryEnabled,
                       Collection<Long> usedTokenCounts) {
        if (currentTokenCount < 0) {
            throw new IllegalArgumentException("currentTokenCount must be non-negative");
        }
        this.currentTokenCount = currentTokenCount;
        this.unorderedEntryEnabled = unorderedEntryEnabled;
        LinkedHashSet<Long> copy = new LinkedHashSet<Long>();
        if (usedTokenCounts != null) {
            for (Long count : usedTokenCounts) {
                if (count == null || count.longValue() < 0) {
                    throw new IllegalArgumentException("usedTokenCounts must be non-negative");
                }
                copy.add(count);
            }
        }
        this.usedTokenCounts = Collections.unmodifiableSet(copy);
    }

    /**
     * Creates state that accepts only tokens newer than {@code currentTokenCount}.
     *
     * @param currentTokenCount highest count already applied by the device
     * @return immutable strict state
     */
    public static TokenState strict(long currentTokenCount) {
        return new TokenState(currentTokenCount, false, Collections.<Long>emptySet());
    }

    /**
     * Creates state that supports safe out-of-order Add Time entry.
     *
     * @param currentTokenCount highest count already applied by the device
     * @param usedTokenCounts counts already consumed in the retained window; {@code null} means an
     *                        enabled but currently empty history
     * @return immutable unordered state with a defensive copy of the supplied counts
     */
    public static TokenState unordered(long currentTokenCount, Collection<Long> usedTokenCounts) {
        return new TokenState(currentTokenCount, true,
                usedTokenCounts == null ? Collections.<Long>emptySet() : usedTokenCounts);
    }

    public long getCurrentTokenCount() { return currentTokenCount; }
    public boolean isUnorderedEntryEnabled() { return unorderedEntryEnabled; }
    public Set<Long> getUsedTokenCounts() { return usedTokenCounts; }
}
