package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.goldcard.paygo.internal.MetricsAuthentication;
import com.goldcard.paygo.metrics.AuthMethod;
import com.goldcard.paygo.metrics.AuthValidationReason;
import com.goldcard.paygo.metrics.MetricsAuthPolicy;
import com.goldcard.paygo.metrics.MetricsDataFormat;
import com.goldcard.paygo.metrics.MetricsDeviceParameters;
import com.goldcard.paygo.metrics.MetricsRequestBuilder;
import com.goldcard.paygo.metrics.MetricsResponseHandler;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MetricsResponseHandlerTest {
    private static final String KEY = "dac86b1a29ab82edc5fbbc41ec9530f6";
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    public void validatesExpandsAndBuildsCompleteResponse() {
        MetricsDataFormat format = format();
        String payload = request(format, AuthMethod.RECURSIVE_DATA_AUTH).buildCondensedPayload();
        MetricsResponseHandler handler = new MetricsResponseHandler(payload, CLOCK);
        handler.setDeviceParameters(parameters(format, 6L, 1_699_999_999L));
        assertTrue(handler.isAuthValid());
        assertEquals(Long.valueOf(7), handler.getRequestCount());
        assertEquals(Long.valueOf(3), handler.getTokenCount());
        assertTrue(handler.expectsTokenAnswer());
        assertTrue(handler.expectsTimeAnswer());

        Map<String, Object> simple = handler.getSimpleMetrics();
        Map<?, ?> data = (Map<?, ?>) simple.get("data");
        assertEquals(BigInteger.valueOf(3), data.get("token_count"));
        List<?> history = (List<?>) simple.get("historical_data");
        assertEquals(BigInteger.valueOf(1_700_000_000L),
                ((Map<?, ?>) history.get(0)).get("timestamp"));
        assertEquals(BigInteger.valueOf(1_700_000_060L),
                ((Map<?, ?>) history.get(1)).get("timestamp"));

        handler.addTokensToAnswer(Arrays.asList("001234567"));
        handler.addTimeToAnswer(NOW.plusSeconds(3600));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("language", "中文");
        handler.addSettingsToAnswer(settings);
        handler.addNewBaseUrlToAnswer("https://example.test/dd");
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("ratio", new BigDecimal("12.3"));
        handler.addExtraDataToAnswer(extra);
        Map<String, Object> answer = handler.buildAnswerMap();
        assertEquals(BigInteger.valueOf(NOW.plusSeconds(3600).getEpochSecond()),
                answer.get("auts"));
        assertEquals(BigInteger.valueOf(3600), answer.get("asl"));
        assertNotNull(answer.get("a"));
        assertTrue(handler.buildAnswerPayload().contains("\\u4e2d\\u6587"));
    }

    @Test
    public void appliesMethodSpecificReplayRules() {
        MetricsDataFormat format = format();
        MetricsResponseHandler timestamp = new MetricsResponseHandler(
                request(format, AuthMethod.TIMESTAMP_AUTH).buildCondensedPayload(), CLOCK);
        timestamp.setDeviceParameters(parameters(format, 100L, 1_700_000_000L));
        assertEquals(AuthValidationReason.TIMESTAMP_REPLAY,
                timestamp.validateAuth().getReason());

        MetricsResponseHandler counter = new MetricsResponseHandler(
                request(format, AuthMethod.COUNTER_AUTH).buildCondensedPayload(), CLOCK);
        counter.setDeviceParameters(parameters(format, 7L, 1L));
        assertEquals(AuthValidationReason.REQUEST_COUNT_REPLAY,
                counter.validateAuth().getReason());
    }

    @Test
    public void simpleAuthRequiresExplicitPolicy() {
        MetricsDataFormat format = format();
        String payload = request(format, AuthMethod.SIMPLE_AUTH).buildCondensedPayload();
        MetricsResponseHandler secure = new MetricsResponseHandler(payload, CLOCK);
        secure.setDeviceParameters(parameters(format, null, null));
        assertEquals(AuthValidationReason.SIMPLE_AUTH_DISABLED,
                secure.validateAuth().getReason());

        MetricsResponseHandler allowed = new MetricsResponseHandler(payload, CLOCK);
        allowed.setDeviceParameters(MetricsDeviceParameters.builder().secretKey(KEY)
                .dataFormat(format).authPolicy(MetricsAuthPolicy.builder()
                        .allowSimpleAuth(true).build()).build());
        assertTrue(allowed.isAuthValid());
    }

    @Test
    public void detectsTamperedSignature() {
        MetricsDataFormat format = format();
        String payload = request(format, AuthMethod.DATA_AUTH).buildCondensedPayload();
        int signatureEnd = payload.length() - 3;
        char replacement = payload.charAt(signatureEnd) == '0' ? '1' : '0';
        String tampered = payload.substring(0, signatureEnd) + replacement
                + payload.substring(signatureEnd + 1);
        MetricsResponseHandler handler = new MetricsResponseHandler(tampered, CLOCK);
        handler.setDeviceParameters(parameters(format, null, null));
        assertEquals(AuthValidationReason.SIGNATURE_MISMATCH,
                handler.validateAuth().getReason());
    }

    @Test
    public void nullExpirationReturnsBothRequestedFieldsAsZero() {
        MetricsDataFormat format = format();
        MetricsResponseHandler handler = new MetricsResponseHandler(
                request(format, AuthMethod.DATA_AUTH).buildCondensedPayload(), CLOCK);
        handler.setDeviceParameters(parameters(format, null, null));
        handler.addTimeToAnswer(null);
        assertEquals(BigInteger.ZERO, handler.buildAnswerMap().get("auts"));
        assertEquals(BigInteger.ZERO, handler.buildAnswerMap().get("asl"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsTimeWhenNotRequested() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token_count", 3);
        String payload = new MetricsRequestBuilder("DEV-1").dataFormat(format())
                .timestamp(1_700_000_000L)
                .secretKey(KEY).authMethod(AuthMethod.DATA_AUTH).data(data)
                .buildSimplePayload();
        new MetricsResponseHandler(payload, CLOCK).addTimeToAnswer(NOW);
    }

    @Test
    public void matchesPythonResponseSignatureVector() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("active_until_timestamp", 1611586670L);
        response.put("active_seconds_left", 3600L);
        response.put("token_list", Arrays.asList("001234567"));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("language", "中文");
        response.put("settings", settings);
        Map<String, Object> extra = new LinkedHashMap<String, Object>();
        extra.put("ratio", new BigDecimal("12.3"));
        response.put("extra_data", extra);
        assertEquals("da1579cb0d528c0775", MetricsAuthentication.signResponse(
                response, KEY, "DEV-中文", 1611583070L, 7L));
    }

    @Test
    public void dataAuthRequiresSignedReplayField() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("value", 1);
        String payload = new MetricsRequestBuilder("DEV-1").data(data)
                .secretKey(KEY).authMethod(AuthMethod.DATA_AUTH).buildSimplePayload();
        MetricsResponseHandler handler = new MetricsResponseHandler(payload, CLOCK);
        handler.setDeviceParameters(MetricsDeviceParameters.builder().secretKey(KEY).build());
        assertEquals(AuthValidationReason.MISSING_REPLAY_FIELD,
                handler.validateAuth().getReason());
    }

    @Test
    public void fillsRelativeAndExplicitHistoricalTimes() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("timestamp", 1_700_000_010L);
        first.put("value", 1);
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("relative_time", 15);
        second.put("value", 2);
        List<Map<String, Object>> history = Arrays.asList(first, second);
        String payload = new MetricsRequestBuilder("DEV-1").dataFormat(format())
                .timestamp(1_700_000_000L)
                .requestCount(1).data(new LinkedHashMap<String, Object>())
                .historicalData(history).secretKey(KEY).authMethod(AuthMethod.DATA_AUTH)
                .buildSimplePayload();
        MetricsResponseHandler handler = new MetricsResponseHandler(payload, CLOCK);
        List<?> expanded = (List<?>) handler.getSimpleMetrics().get("historical_data");
        assertEquals(BigInteger.valueOf(1_700_000_010L),
                ((Map<?, ?>) expanded.get(0)).get("timestamp"));
        assertEquals(BigInteger.valueOf(1_700_000_025L),
                ((Map<?, ?>) expanded.get(1)).get("timestamp"));
        assertFalse(((Map<?, ?>) expanded.get(1)).containsKey("relative_time"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void returnedNestedCollectionsDoNotExposeResponseState() {
        MetricsDataFormat format = format();
        MetricsResponseHandler handler = new MetricsResponseHandler(
                request(format, AuthMethod.DATA_AUTH).buildCondensedPayload(), CLOCK);
        handler.setDeviceParameters(parameters(format, null, null));
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("language", "zh-CN");
        handler.addSettingsToAnswer(settings);
        Map<String, Object> first = handler.buildAnswerMap();
        ((Map<String, Object>) first.get("st")).put("language", "changed");
        assertEquals("zh-CN", ((Map<?, ?>) handler.buildAnswerMap().get("st"))
                .get("language"));
    }

    private static MetricsRequestBuilder request(MetricsDataFormat format, AuthMethod method) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token_count", 3);
        data.put("active_until_timestamp_requested", true);
        data.put("active_seconds_left_requested", true);
        List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("voltage", 12.3);
        history.add(first);
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("voltage", 12.4);
        history.add(second);
        return new MetricsRequestBuilder("DEV-1").dataFormat(format)
                .timestamp(1_700_000_000L).requestCount(7)
                .dataCollectionTimestamp(1_700_000_000L)
                .data(data).historicalData(history).secretKey(KEY).authMethod(method);
    }

    private static MetricsDataFormat format() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", 42);
        value.put("data_order", Arrays.asList("token_count",
                "active_until_timestamp_requested", "active_seconds_left_requested"));
        value.put("historical_data_order", Arrays.asList("voltage"));
        value.put("historical_data_interval", 60);
        return MetricsDataFormat.of(value);
    }

    private static MetricsDeviceParameters parameters(MetricsDataFormat format, Long count,
                                                       Long timestamp) {
        return MetricsDeviceParameters.builder().secretKey(KEY).dataFormat(format)
                .lastRequestCount(count).lastRequestTimestamp(timestamp).build();
    }
}
