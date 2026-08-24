package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenStatus;
import com.goldcard.paygo.token.TokenType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class TokenCompatibilityTest {
    @Test
    public void matchesPythonVectorsAndRoundTrips() throws Exception {
        List<JSONObject> vectors = loadVectors();
        int compatible = 0;
        int rejected = 0;
        for (JSONObject vector : vectors) {
            TokenType type = TokenType.valueOf(vector.getString("token_type"));
            boolean extended = vector.getBooleanValue("extended_token");
            TokenGenerationRequest.Builder builder = TokenGenerationRequest.builder()
                    .secretKey(vector.getString("key"))
                    .currentTokenCount(vector.getLongValue("count"))
                    .tokenType(type)
                    .startingCode(vector.getLong("starting_code"))
                    .restrictedDigitSet(vector.getBooleanValue("restricted_digit_set"))
                    .extendedToken(extended);
            if (type == TokenType.ADD_TIME || type == TokenType.SET_TIME) {
                builder.activationValue(vector.getBigDecimal("value_raw"));
            }
            if (extended && (type == TokenType.DISABLE_PAYG || type == TokenType.COUNTER_SYNC)) {
                try {
                    builder.build();
                    fail("ambiguous extended token must be rejected");
                } catch (IllegalArgumentException expected) {
                    rejected++;
                }
                continue;
            }

            TokenGenerationResult generated = OpenPaygo.generateToken(builder.build());
            assertEquals(vector.getLongValue("new_count"), generated.getUpdatedTokenCount());
            assertEquals(vector.getString("token"), generated.getToken());

            TokenDecodeResult decoded = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                    .token(generated.getToken())
                    .secretKey(vector.getString("key"))
                    .tokenState(TokenState.unordered(vector.getLongValue("count"), null))
                    .startingCode(vector.getLong("starting_code"))
                    .restrictedDigitSet(vector.getBooleanValue("restricted_digit_set"))
                    .build());
            assertEquals(TokenStatus.VALID, decoded.getStatus());
            assertEquals(type, decoded.getTokenType().get());
            assertEquals(generated.getUpdatedTokenCount(),
                    decoded.getUpdatedState().get().getCurrentTokenCount());
            if (type == TokenType.ADD_TIME || type == TokenType.SET_TIME) {
                assertEquals(0, vector.getBigDecimal("value_raw")
                        .compareTo(decoded.getActivationValue().get()));
            }
            compatible++;
        }
        assertEquals(72, compatible);
        assertEquals(8, rejected);
        assertTrue(vectors.size() == compatible + rejected);
        System.out.println("Token vectors: compatible=" + compatible + ", rejected=" + rejected);
    }

    private static List<JSONObject> loadVectors() throws IOException {
        InputStream input = TokenCompatibilityTest.class
                .getResourceAsStream("/token_vectors.json");
        if (input == null) {
            throw new IOException("Missing classpath resource: token_vectors.json");
        }
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                json.append(buffer, 0, read);
            }
        }
        return JSON.parseArray(json.toString(), JSONObject.class);
    }
}
