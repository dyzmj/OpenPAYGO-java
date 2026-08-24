package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenStatus;
import com.goldcard.paygo.token.TokenType;
import com.goldcard.paygo.token.TokenValidationPolicy;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.Test;

public class TokenDecodeTest {
    private static final String KEY = "a29ab82edc5fbbc41ec9530f6dac86b1";
    private static final long STARTING_CODE = 123456789L;

    @Test
    public void invalidAndAlreadyUsedAreResults() {
        TokenDecodeResult invalid = decode("000000000", TokenState.strict(1), false,
                TokenValidationPolicy.defaults());
        assertEquals(TokenStatus.INVALID, invalid.getStatus());

        TokenGenerationResult generated = generate(1, TokenType.ADD_TIME, 1);
        TokenDecodeResult used = decode(generated.getToken(),
                TokenState.unordered(generated.getUpdatedTokenCount(),
                        Collections.singleton(generated.getUpdatedTokenCount())),
                false, TokenValidationPolicy.defaults());
        assertEquals(TokenStatus.ALREADY_USED, used.getStatus());
    }

    @Test
    public void acceptsUnusedOlderAddTimeOnlyWithinWindow() {
        TokenGenerationResult first = generate(1, TokenType.ADD_TIME, 2);
        TokenGenerationResult second = generate(first.getUpdatedTokenCount(), TokenType.ADD_TIME, 3);
        TokenDecodeResult latest = decode(second.getToken(), TokenState.unordered(1, null), false,
                TokenValidationPolicy.defaults());
        assertEquals(TokenStatus.VALID, latest.getStatus());
        TokenDecodeResult older = decode(first.getToken(), latest.getUpdatedState().get(), false,
                TokenValidationPolicy.defaults());
        assertEquals(TokenStatus.VALID, older.getStatus());
        assertTrue(older.getUpdatedState().get().getUsedTokenCounts()
                .contains(first.getUpdatedTokenCount()));
    }

    @Test
    public void setTimeBlocksOlderAddTime() {
        TokenGenerationResult add = generate(1, TokenType.ADD_TIME, 1);
        TokenGenerationResult set = generate(add.getUpdatedTokenCount(), TokenType.SET_TIME, 5);
        TokenDecodeResult setResult = decode(set.getToken(), TokenState.unordered(1, null), false,
                TokenValidationPolicy.defaults());
        TokenDecodeResult oldAdd = decode(add.getToken(), setResult.getUpdatedState().get(), false,
                TokenValidationPolicy.defaults());
        assertEquals(TokenStatus.ALREADY_USED, oldAdd.getStatus());
    }

    @Test
    public void disableAndCounterSyncBlockOlderAddTime() {
        for (TokenType blockingType : new TokenType[] {
                TokenType.DISABLE_PAYG, TokenType.COUNTER_SYNC }) {
            TokenGenerationResult add = generate(1, TokenType.ADD_TIME, 1);
            TokenGenerationResult blocking = generateFromCount(
                    add.getUpdatedTokenCount(), blockingType, null);
            TokenDecodeResult blockingResult = decode(blocking.getToken(),
                    TokenState.unordered(1, null), false, TokenValidationPolicy.defaults());
            assertEquals(TokenStatus.VALID, blockingResult.getStatus());
            assertEquals(TokenStatus.ALREADY_USED, decode(add.getToken(),
                    blockingResult.getUpdatedState().get(), false,
                    TokenValidationPolicy.defaults()).getStatus());
        }
    }

    @Test
    public void normalForwardJumpHonorsBoundary() {
        TokenGenerationResult atBoundary = generate(63, TokenType.ADD_TIME, 1);
        TokenGenerationResult outside = generate(64, TokenType.ADD_TIME, 1);
        assertEquals(TokenStatus.VALID, decode(atBoundary.getToken(), TokenState.strict(0),
                false, TokenValidationPolicy.defaults()).getStatus());
        assertEquals(TokenStatus.INVALID, decode(outside.getToken(), TokenState.strict(0),
                false, TokenValidationPolicy.defaults()).getStatus());
    }

    @Test
    public void strictStateDoesNotTrackUsedCounts() {
        TokenGenerationResult token = generate(1, TokenType.ADD_TIME, 1);
        TokenDecodeResult decoded = decode(token.getToken(), TokenState.strict(1), false,
                TokenValidationPolicy.defaults());
        assertFalse(decoded.getUpdatedState().get().isUnorderedEntryEnabled());
        assertTrue(decoded.getUpdatedState().get().getUsedTokenCounts().isEmpty());
    }

    @Test
    public void counterResetRequiresOptIn() {
        String resetToken = "123456788"; // Starting code 123456789 with value 999 at count 0.
        TokenState farAhead = TokenState.unordered(200, null);
        assertEquals(TokenStatus.ALREADY_USED,
                decode(resetToken, farAhead, false, TokenValidationPolicy.defaults()).getStatus());
        TokenValidationPolicy enabled = TokenValidationPolicy.builder()
                .counterResetEnabled(true).build();
        TokenDecodeResult accepted = decode(resetToken, farAhead, false, enabled);
        assertEquals(TokenStatus.VALID, accepted.getStatus());
        assertEquals(0L, accepted.getUpdatedState().get().getCurrentTokenCount());
    }

    @Test
    public void explicitZeroStartingCodeIsNotMissing() {
        TokenGenerationResult token = OpenPaygo.generateToken(TokenGenerationRequest.builder()
                .secretKey(KEY).currentTokenCount(1).tokenType(TokenType.ADD_TIME)
                .activationValue(BigDecimal.ONE).startingCode(0L).build());
        TokenDecodeResult decoded = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                .token(token.getToken()).secretKey(KEY).tokenState(TokenState.strict(1))
                .startingCode(0L).build());
        assertEquals(TokenStatus.VALID, decoded.getStatus());
    }

    @Test
    public void generatedStartingCodeRoundTripsAndUsesHalfEvenRounding() {
        TokenGenerationResult token = OpenPaygo.generateToken(TokenGenerationRequest.builder()
                .secretKey(KEY).currentTokenCount(1).tokenType(TokenType.ADD_TIME)
                .activationValue(new BigDecimal("2.5")).build());
        TokenDecodeResult decoded = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                .token(token.getToken()).secretKey(KEY).tokenState(TokenState.strict(1)).build());
        assertEquals(TokenStatus.VALID, decoded.getStatus());
        assertEquals(Long.valueOf(2L), decoded.getRawValue().get());
    }

    @Test
    public void generationIsDeterministicForSameRequest() {
        TokenGenerationRequest request = TokenGenerationRequest.builder()
                .secretKey(KEY).currentTokenCount(1).tokenType(TokenType.ADD_TIME)
                .activationValue(new BigDecimal("2.5")).build();
        TokenGenerationResult first = OpenPaygo.generateToken(request);
        TokenGenerationResult second = OpenPaygo.generateToken(request);
        assertEquals(first.getToken(), second.getToken());
        assertEquals(first.getUpdatedTokenCount(), second.getUpdatedTokenCount());
    }

    @Test
    public void counterSyncHonorsBackwardWindow() {
        TokenGenerationResult sync = generateFromCount(99, TokenType.COUNTER_SYNC, null);
        TokenValidationPolicy narrow = TokenValidationPolicy.builder()
                .counterSyncBackwardWindow(10).build();
        assertEquals(TokenStatus.ALREADY_USED,
                decode(sync.getToken(), TokenState.unordered(200, null), false, narrow)
                        .getStatus());
        TokenValidationPolicy wide = TokenValidationPolicy.builder()
                .counterSyncBackwardWindow(101).build();
        TokenDecodeResult accepted = decode(sync.getToken(), TokenState.unordered(200, null),
                false, wide);
        assertEquals(TokenStatus.VALID, accepted.getStatus());
        assertEquals(sync.getUpdatedTokenCount(),
                accepted.getUpdatedState().get().getCurrentTokenCount());
    }

    @Test
    public void counterSyncHonorsForwardWindow() {
        TokenGenerationResult atBoundary = generateFromCount(297,
                TokenType.COUNTER_SYNC, null);
        TokenGenerationResult outside = generateFromCount(299,
                TokenType.COUNTER_SYNC, null);
        assertEquals(TokenStatus.VALID, decode(atBoundary.getToken(),
                TokenState.unordered(200, null), false,
                TokenValidationPolicy.defaults()).getStatus());
        assertEquals(TokenStatus.INVALID, decode(outside.getToken(),
                TokenState.unordered(200, null), false,
                TokenValidationPolicy.defaults()).getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidSecretKey() {
        OpenPaygo.generateToken(TokenGenerationRequest.builder()
                .secretKey("invalid").currentTokenCount(1)
                .tokenType(TokenType.ADD_TIME).activationValue(BigDecimal.ONE).build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsActivationValueOutsideStandardRange() {
        generate(1, TokenType.ADD_TIME, 996);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidValueDivider() {
        TokenGenerationRequest.builder().secretKey(KEY).currentTokenCount(1)
                .tokenType(TokenType.ADD_TIME).activationValue(BigDecimal.ONE)
                .valueDivider(0).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongTokenLength() {
        decode("123", TokenState.strict(1), false, TokenValidationPolicy.defaults());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongRestrictedDigits() {
        decode("111111111111115", TokenState.strict(1), true,
                TokenValidationPolicy.defaults());
    }

    private static TokenGenerationResult generate(long current, TokenType type, long value) {
        return generateFromCount(current, type, BigDecimal.valueOf(value));
    }

    private static TokenGenerationResult generateFromCount(long current, TokenType type,
                                                            BigDecimal value) {
        TokenGenerationRequest.Builder builder = TokenGenerationRequest.builder()
                .secretKey(KEY).currentTokenCount(current).tokenType(type)
                .startingCode(STARTING_CODE);
        if (value != null) builder.activationValue(value);
        return OpenPaygo.generateToken(builder.build());
    }

    private static TokenDecodeResult decode(String token, TokenState state, boolean restricted,
                                            TokenValidationPolicy policy) {
        return OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                .token(token).secretKey(KEY).tokenState(state)
                .startingCode(STARTING_CODE).restrictedDigitSet(restricted)
                .validationPolicy(policy).build());
    }
}
