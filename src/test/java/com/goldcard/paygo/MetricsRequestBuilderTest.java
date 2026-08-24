package com.goldcard.paygo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.goldcard.paygo.internal.MetricsAuthentication;
import com.goldcard.paygo.metrics.AuthMethod;
import com.goldcard.paygo.metrics.MetricsDataFormat;
import com.goldcard.paygo.metrics.MetricsDeviceParameters;
import com.goldcard.paygo.metrics.MetricsRequestBuilder;
import com.goldcard.paygo.metrics.MetricsResponseHandler;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MetricsRequestBuilderTest {
    private static final String KEY = "dac86b1a29ab82edc5fbbc41ec9530f6";

    @Test
    public void matchesFivePythonAuthenticationVectors() throws Exception {
        Map<String, Object> request = vectorRequest();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/metrics_vectors.jsonl"), StandardCharsets.UTF_8));
        String line;
        int count = 0;
        while ((line = reader.readLine()) != null) {
            JSONObject vector = JSON.parseObject(line);
            if ("RESPONSE".equals(vector.getString("method"))) continue;
            AuthMethod method = AuthMethod.valueOf(vector.getString("method"));
            assertEquals(vector.getString("signature"),
                    MetricsAuthentication.signRequest(request, method, KEY));
            count++;
        }
        reader.close();
        assertEquals(5, count);
    }

    @Test
    public void buildsCondensedRequestInDataFormatOrder() {
        MetricsRequestBuilder builder = new MetricsRequestBuilder("DEV-1")
                .dataFormat(format()).timestamp(1000).requestCount(2)
                .secretKey(KEY).authMethod(AuthMethod.RECURSIVE_DATA_AUTH)
                .data(currentData()).historicalData(history());
        String payload = builder.buildCondensedPayload();
        assertEquals("{\"sn\":\"DEV-1\",\"df\":42,\"ts\":1000,\"rc\":2,"
                        + "\"d\":[3,\"1.2.3\"],\"hd\":[[12.3,true]],\"a\":"
                        + "\"ra" + builder.buildCondensedMap().get("a").toString().substring(2)
                        + "\"}", payload);
        assertFalse(payload.contains("null"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownDataVariable() {
        Map<String, Object> data = currentData();
        data.put("unknown", 1);
        new MetricsRequestBuilder("DEV-1").dataFormat(format()).data(data)
                .buildCondensedPayload();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDeviceSecretKeyEarly() {
        com.goldcard.paygo.metrics.MetricsDeviceParameters.builder()
                .secretKey("not-a-128-bit-key").build();
    }

    @Test
    public void simpleAndCondensedRequestsExpandToSameDataDeterministically() {
        Map<String, Object> data = currentData();
        List<Map<String, Object>> history = history();
        Map<String, Object> formatValue = new LinkedHashMap<String, Object>();
        formatValue.put("id", 42);
        formatValue.put("data_order", java.util.Arrays.asList("token_count", "firmware"));
        formatValue.put("historical_data_order", java.util.Arrays.asList("voltage", "ok"));
        formatValue.put("historical_data_interval", 60);
        MetricsDataFormat format = MetricsDataFormat.of(formatValue);
        MetricsRequestBuilder builder = new MetricsRequestBuilder("DEV-1")
                .dataFormat(format).timestamp(1000).dataCollectionTimestamp(1000)
                .data(data).historicalData(history);
        String simplePayload = builder.buildSimplePayload();
        String condensedPayload = builder.buildCondensedPayload();
        assertEquals(simplePayload, builder.buildSimplePayload());
        assertEquals(condensedPayload, builder.buildCondensedPayload());

        MetricsResponseHandler simple = new MetricsResponseHandler(simplePayload);
        MetricsResponseHandler condensed = new MetricsResponseHandler(condensedPayload);
        MetricsDeviceParameters parameters = MetricsDeviceParameters.builder()
                .dataFormat(format).build();
        simple.setDeviceParameters(parameters);
        condensed.setDeviceParameters(parameters);
        assertEquals(simple.getSimpleMetrics().get("data"),
                condensed.getSimpleMetrics().get("data"));
        assertEquals(simple.getSimpleMetrics().get("historical_data"),
                condensed.getSimpleMetrics().get("historical_data"));
    }

    private static Map<String, Object> vectorRequest() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("token_count", BigInteger.valueOf(3));
        data.put("label", "中文");
        data.put("ratio", new BigDecimal("12.3"));
        data.put("nullable", null);
        Map<String, Object> step = new LinkedHashMap<String, Object>();
        step.put("energy", new BigDecimal("1.25"));
        step.put("ok", true);
        List<Object> history = new ArrayList<Object>();
        history.add(step);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("serial_number", "DEV-中文");
        request.put("timestamp", BigInteger.valueOf(1611583070L));
        request.put("request_count", BigInteger.valueOf(7));
        request.put("data", data);
        request.put("historical_data", history);
        return request;
    }

    private static MetricsDataFormat format() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("id", 42);
        value.put("data_order", java.util.Arrays.asList("token_count", "firmware", "optional"));
        value.put("historical_data_order", java.util.Arrays.asList("voltage", "ok", "optional"));
        value.put("historical_data_interval", 60);
        return MetricsDataFormat.of(value);
    }

    private static Map<String, Object> currentData() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("token_count", 3);
        value.put("firmware", "1.2.3");
        return value;
    }

    private static List<Map<String, Object>> history() {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("voltage", 12.3);
        value.put("ok", true);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        result.add(value);
        return result;
    }
}
