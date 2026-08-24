package com.goldcard.paygo.metrics;

import com.goldcard.paygo.internal.MetricsAuthentication;
import com.goldcard.paygo.internal.MetricsJsonCodec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds deterministic OpenPAYGO Metrics requests in simple or condensed wire form.
 *
 * <p>The builder makes defensive copies of dynamic JSON values. Field insertion order and the Data
 * Format order are preserved because they are part of the authentication payload. Instances are
 * mutable, intended for one request, and are not thread-safe.</p>
 *
 * @author dyzmj
 */
public final class MetricsRequestBuilder {
    private final String serialNumber;
    private final LinkedHashMap<String, Object> header = new LinkedHashMap<String, Object>();
    private MetricsDataFormat dataFormat;
    private String secretKey;
    private AuthMethod authMethod;
    private Map<String, Object> data = Collections.emptyMap();
    private List<Map<String, Object>> historicalData = Collections.emptyList();

    /**
     * Starts a request for one device.
     *
     * @param serialNumber stable device serial included in every authentication method
     */
    public MetricsRequestBuilder(String serialNumber) {
        if (serialNumber == null || serialNumber.isEmpty()) {
            throw new IllegalArgumentException("serialNumber is required");
        }
        this.serialNumber = serialNumber;
    }

    public MetricsRequestBuilder secretKey(String value) { this.secretKey = value; return this; }
    public MetricsRequestBuilder authMethod(AuthMethod value) { this.authMethod = value; return this; }
    public MetricsRequestBuilder dataFormat(MetricsDataFormat value) { this.dataFormat = value; return this; }

    public MetricsRequestBuilder timestamp(long value) {
        requirePositive(value, "timestamp");
        header.put("timestamp", BigInteger.valueOf(value));
        return this;
    }

    public MetricsRequestBuilder requestCount(long value) {
        requirePositive(value, "requestCount");
        header.put("request_count", BigInteger.valueOf(value));
        return this;
    }

    public MetricsRequestBuilder dataCollectionTimestamp(long value) {
        requirePositive(value, "dataCollectionTimestamp");
        header.put("data_collection_timestamp", BigInteger.valueOf(value));
        return this;
    }

    /**
     * Replaces the current metrics object with a recursively validated defensive copy.
     *
     * @param value current metric values keyed by Data Format field name
     * @return this builder
     */
    public MetricsRequestBuilder data(Map<String, Object> value) {
        this.data = MetricsJsonCodec.immutableObject(value);
        return this;
    }

    /**
     * Replaces historical samples with defensive copies.
     *
     * <p>Each sample needs an explicit timestamp unless the selected Data Format defines a fixed
     * interval. Relative timestamps are accepted in simple form and resolved by the server.</p>
     *
     * @param value historical metric samples in chronological order
     * @return this builder
     */
    public MetricsRequestBuilder historicalData(List<Map<String, Object>> value) {
        if (value == null) throw new IllegalArgumentException("historicalData is required");
        List<Map<String, Object>> copy = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> step : value) {
            Map<String, Object> normalized = MetricsJsonCodec.immutableObject(step);
            if ((dataFormat == null || dataFormat.getHistoricalDataInterval() == null)
                    && !normalized.containsKey("timestamp")) {
                throw new IllegalArgumentException(
                        "Historical data requires timestamp when no interval is defined");
            }
            copy.add(normalized);
        }
        this.historicalData = Collections.unmodifiableList(copy);
        return this;
    }

    /**
     * Builds the readable field-name representation. Authentication, when configured, is computed
     * over this logical representation before any top-level key abbreviation.
     *
     * @return immutable simple request map
     */
    public Map<String, Object> buildSimpleMap() {
        return MetricsJsonCodec.immutableObject(build(false));
    }

    /**
     * Builds the bandwidth-efficient representation using Data Format arrays and abbreviated keys.
     *
     * @return immutable condensed request map
     */
    public Map<String, Object> buildCondensedMap() {
        return MetricsJsonCodec.immutableObject(build(true));
    }

    /**
     * Returns deterministic compact JSON for the simple representation.
     *
     * @return simple request JSON
     */
    public String buildSimplePayload() { return MetricsJsonCodec.toJson(build(false)); }

    /**
     * Returns deterministic compact JSON for the condensed representation.
     *
     * @return condensed request JSON
     */
    public String buildCondensedPayload() { return MetricsJsonCodec.toJson(build(true)); }

    private Map<String, Object> build(boolean condensed) {
        if (condensed && dataFormat == null) {
            throw new IllegalStateException("No Data Format provided for condensed request");
        }
        LinkedHashMap<String, Object> request = baseRequest();
        request.put("data", condensed ? condenseData() : MetricsJsonCodec.copyObject(data));
        request.put("historical_data", condensed
                ? condenseHistoricalData() : copyHistoricalData());
        if (authMethod != null) {
            if (secretKey == null) {
                throw new IllegalStateException("secretKey is required when authMethod is set");
            }
            request.put("auth", MetricsAuthentication.signRequest(request, authMethod, secretKey));
        }
        return condensed ? MetricsJsonCodec.condenseTopLevel(request) : request;
    }

    private LinkedHashMap<String, Object> baseRequest() {
        LinkedHashMap<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("serial_number", serialNumber);
        if (dataFormat != null) {
            if (dataFormat.getId() != null) request.put("data_format_id", dataFormat.getId());
            else request.put("data_format", dataFormat.getDefinition());
        }
        request.putAll(header);
        return request;
    }

    private List<Object> condenseData() {
        if (!data.isEmpty() && dataFormat.getDataOrder().isEmpty()) {
            throw new IllegalArgumentException("Data Format does not contain data_order");
        }
        return orderObject(data, dataFormat.getDataOrder(), "data");
    }

    private List<Object> condenseHistoricalData() {
        if (!historicalData.isEmpty() && dataFormat.getHistoricalDataOrder().isEmpty()) {
            throw new IllegalArgumentException("Data Format does not contain historical_data_order");
        }
        List<Object> result = new ArrayList<Object>();
        for (Map<String, Object> step : historicalData) {
            result.add(orderObject(step, dataFormat.getHistoricalDataOrder(), "historical data"));
        }
        return result;
    }

    private static List<Object> orderObject(Map<String, Object> source, List<String> order,
                                            String name) {
        // Unknown variables cannot be serialized safely because peers would assign them no stable
        // array index. Trailing nulls are removed without changing indexes of preceding variables.
        Map<String, Object> remaining = MetricsJsonCodec.copyObject(source);
        List<Object> result = new ArrayList<Object>();
        for (String variable : order) {
            result.add(remaining.remove(variable));
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException("Additional variables not present in " + name
                    + " format: " + remaining.keySet());
        }
        while (!result.isEmpty() && result.get(result.size() - 1) == null) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private List<Object> copyHistoricalData() {
        List<Object> result = new ArrayList<Object>();
        for (Map<String, Object> step : historicalData) {
            result.add(MetricsJsonCodec.copyObject(step));
        }
        return result;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
