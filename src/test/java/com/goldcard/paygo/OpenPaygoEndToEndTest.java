package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.goldcard.paygo.metrics.AuthMethod;
import com.goldcard.paygo.metrics.MetricsDataFormat;
import com.goldcard.paygo.metrics.MetricsDeviceParameters;
import com.goldcard.paygo.metrics.MetricsRequestBuilder;
import com.goldcard.paygo.metrics.MetricsResponseHandler;
import com.goldcard.paygo.token.TokenDecodeRequest;
import com.goldcard.paygo.token.TokenDecodeResult;
import com.goldcard.paygo.token.TokenGenerationRequest;
import com.goldcard.paygo.token.TokenGenerationResult;
import com.goldcard.paygo.token.TokenState;
import com.goldcard.paygo.token.TokenStatus;
import com.goldcard.paygo.token.TokenType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class OpenPaygoEndToEndTest {
    private static final String KEY = "dac86b1a29ab82edc5fbbc41ec9530f6";

    @Test
    public void serverTokenIsAcceptedOnceByDevice() {
        TokenGenerationResult generated = OpenPaygo.generateToken(
                TokenGenerationRequest.builder().secretKey(KEY).currentTokenCount(1)
                        .tokenType(TokenType.ADD_TIME).activationValue(BigDecimal.valueOf(7))
                        .build());
        TokenState initial = TokenState.unordered(1, null);
        TokenDecodeResult first = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                .token(generated.getToken()).secretKey(KEY).tokenState(initial).build());
        assertEquals(TokenStatus.VALID, first.getStatus());
        assertEquals(0, BigDecimal.valueOf(7).compareTo(first.getActivationValue().get()));
        TokenDecodeResult repeated = OpenPaygo.decodeToken(TokenDecodeRequest.builder()
                .token(generated.getToken()).secretKey(KEY)
                .tokenState(first.getUpdatedState().get()).build());
        assertEquals(TokenStatus.ALREADY_USED, repeated.getStatus());
    }

    @Test
    public void deviceMetricsRoundTripProducesSignedCompleteResponse() {
        MetricsDataFormat format = dataFormat();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token_count", 3);
        data.put("active_until_timestamp_requested", true);
        data.put("active_seconds_left_requested", true);
        Map<String, Object> historyStep = new LinkedHashMap<String, Object>();
        historyStep.put("voltage", 12.3);
        String request = new MetricsRequestBuilder("DEVICE-001").dataFormat(format)
                .timestamp(1_700_000_000L).requestCount(7).data(data)
                .historicalData(Collections.singletonList(historyStep))
                .secretKey(KEY).authMethod(AuthMethod.RECURSIVE_DATA_AUTH)
                .buildCondensedPayload();

        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
        MetricsResponseHandler handler = new MetricsResponseHandler(request, clock);
        handler.setDeviceParameters(MetricsDeviceParameters.builder().secretKey(KEY)
                .dataFormat(format).lastRequestCount(6L)
                .lastRequestTimestamp(1_699_999_999L).build());
        assertTrue(handler.isAuthValid());
        assertEquals(1, ((java.util.List<?>) handler.getSimpleMetrics()
                .get("historical_data")).size());
        handler.addTokensToAnswer(Arrays.asList("001234567"));
        handler.addTimeToAnswer(clock.instant().plusSeconds(3600));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("language", "zh-CN");
        handler.addSettingsToAnswer(settings);
        Map<String, Object> response = handler.buildAnswerMap();
        assertTrue(response.containsKey("tkl"));
        assertTrue(response.containsKey("auts"));
        assertTrue(response.containsKey("asl"));
        assertTrue(response.containsKey("st"));
        assertTrue(response.containsKey("a"));
    }

    private static MetricsDataFormat dataFormat() {
        Map<String, Object> format = new LinkedHashMap<String, Object>();
        format.put("id", 42);
        format.put("data_order", Arrays.asList("token_count",
                "active_until_timestamp_requested", "active_seconds_left_requested"));
        format.put("historical_data_order", Arrays.asList("voltage"));
        format.put("historical_data_interval", 60);
        return MetricsDataFormat.of(format);
    }
}
