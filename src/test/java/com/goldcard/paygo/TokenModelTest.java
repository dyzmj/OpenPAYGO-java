package com.goldcard.paygo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.goldcard.paygo.token.TokenState;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class TokenModelTest {
    @Test
    public void distinguishesStrictAndEmptyUnorderedState() {
        assertFalse(TokenState.strict(1).isUnorderedEntryEnabled());
        assertTrue(TokenState.unordered(1, null).isUnorderedEntryEnabled());
        assertTrue(TokenState.unordered(1, null).getUsedTokenCounts().isEmpty());
    }

    @Test
    public void defensivelyCopiesUsedCounts() {
        List<Long> counts = new ArrayList<Long>();
        counts.add(2L);
        TokenState state = TokenState.unordered(2, counts);
        counts.add(4L);
        assertFalse(state.getUsedTokenCounts().contains(4L));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void exposesImmutableUsedCounts() {
        TokenState.unordered(1, null).getUsedTokenCounts().add(2L);
    }
}
