package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;

import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenStatus;
import com.goldcard.paygo.token.TokenType;
import java.math.BigDecimal;
import org.junit.Test;

/** Compatibility check for OpenPAYGO-js 0.0.7 example.js. */
public class JavaScriptExampleCompatibilityTest {
    private static final String SECRET_KEY = "bc41ec9530f6dac86b1a29ab82edc5fb";
    private static final long STARTING_CODE = 801251L;
    private static final String EXPECTED_TOKEN = "983032258";

    @Test
    public void matchesJavaScriptExampleOutputAndDecodesIt() {
        TokenGenerationResult generated = OpenPaygo.generateToken(
                TokenGenerationRequest.builder()
                        .tokenType(TokenType.ADD_TIME)
                        .secretKey(SECRET_KEY)
                        .currentTokenCount(2)
                        .startingCode(STARTING_CODE)
                        .restrictedDigitSet(false)
                        .extendedToken(false)
                        .activationValue(BigDecimal.valueOf(7))
                        .build());

        assertEquals(EXPECTED_TOKEN, generated.getToken());
        assertEquals(4L, generated.getUpdatedTokenCount());

        TokenDecodeResult decoded = OpenPaygo.decodeToken(
                TokenDecodeRequest.builder()
                        .token(EXPECTED_TOKEN)
                        .secretKey(SECRET_KEY)
                        .tokenState(TokenState.strict(2))
                        .startingCode(STARTING_CODE)
                        .restrictedDigitSet(false)
                        .build());

        assertEquals(TokenStatus.VALID, decoded.getStatus());
        assertEquals(TokenType.ADD_TIME, decoded.getTokenType().get());
        assertEquals(Long.valueOf(7L), decoded.getRawValue().get());
        assertEquals(4L, decoded.getUpdatedState().get().getCurrentTokenCount());
    }
}
