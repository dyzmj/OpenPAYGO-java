package com.goldcard.paygo.metrics;

import com.goldcard.paygo.internal.MetricsAuthentication;
import com.goldcard.paygo.internal.MetricsJsonCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses, authenticates, expands, and answers one OpenPAYGO Metrics request.
 *
 * <p>Construction parses only the wire structure. Device-specific key, replay counters, and Data
 * Format are supplied separately through {@link #setDeviceParameters(MetricsDeviceParameters)}.
 * The handler is mutable while an answer is assembled and must not be shared across requests.</p>
 *
 * @author dyzmj
 */
public final class MetricsResponseHandler {
    private final Map<String, Object> request;
    private final Clock clock;
    private final long effectiveTimestamp;
    private final LinkedHashMap<String, Object> response = new LinkedHashMap<String, Object>();
    private MetricsDeviceParameters parameters = MetricsDeviceParameters.builder().build();
    private MetricsDataFormat dataFormat;

    /**
     * Parses a request using the system UTC clock for missing timestamps and relative time answers.
     *
     * @param receivedPayload simple or condensed Metrics JSON object
     * @throws MetricsException if the payload is malformed or has a non-object root
     */
    public MetricsResponseHandler(String receivedPayload) {
        this(receivedPayload, Clock.systemUTC());
    }

    /**
     * Parses a request with an injectable clock, allowing deterministic replay and expiry handling.
     *
     * @param receivedPayload simple or condensed Metrics JSON object
     * @param clock clock used when the request omits a timestamp and for remaining-time responses
     * @throws MetricsException if the payload is malformed or has a non-object root
     */
    public MetricsResponseHandler(String receivedPayload, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.request = MetricsJsonCodec.expandTopLevel(
                MetricsJsonCodec.parseObject(receivedPayload));
        Object embedded = request.get("data_format");
        if (embedded instanceof Map) {
            this.dataFormat = MetricsDataFormat.of(castObject(embedded));
        }
        Long timestamp = optionalLong(request.get("timestamp"), "timestamp");
        this.effectiveTimestamp = timestamp == null
                ? clock.instant().getEpochSecond() : timestamp.longValue();
    }

    public String getDeviceSerial() {
        Object value = request.get("serial_number");
        return value instanceof String ? (String) value : null;
    }

    public Object getDataFormatId() {
        return MetricsJsonCodec.copyValue(request.get("data_format_id"));
    }

    /**
     * Supplies server-side state required for signature and replay validation. A Data Format in the
     * parameters takes precedence over an embedded format from the request.
     *
     * @param value device identity, authentication policy, replay state, and Data Format
     */
    public void setDeviceParameters(MetricsDeviceParameters value) {
        this.parameters = Objects.requireNonNull(value, "parameters");
        if (value.getDataFormat() != null) this.dataFormat = value.getDataFormat();
    }

    /**
     * Verifies method prefix, SipHash signature, authentication policy, and method-specific replay
     * fields without throwing for normal authentication failures.
     *
     * @return a structured result describing success or the exact rejection reason
     */
    public AuthValidationResult validateAuth() {
        Object authValue = request.get("auth");
        if (!(authValue instanceof String) || ((String) authValue).length() < 3) {
            return AuthValidationResult.invalid(null, AuthValidationReason.MISSING_AUTH);
        }
        if (parameters.getSecretKey() == null) {
            return AuthValidationResult.invalid(null, AuthValidationReason.MISSING_SECRET_KEY);
        }
        String auth = (String) authValue;
        AuthMethod method;
        try {
            method = AuthMethod.fromCode(auth.substring(0, 2));
        } catch (IllegalArgumentException exception) {
            return AuthValidationResult.invalid(null, AuthValidationReason.UNKNOWN_AUTH_METHOD);
        }
        String expected;
        try {
            expected = MetricsAuthentication.signRequest(request, method,
                    parameters.getSecretKey());
        } catch (IllegalArgumentException exception) {
            return AuthValidationResult.invalid(method, AuthValidationReason.MISSING_REPLAY_FIELD);
        }
        if (!MessageDigest.isEqual(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return AuthValidationResult.invalid(method, AuthValidationReason.SIGNATURE_MISMATCH);
        }
        if (method == AuthMethod.SIMPLE_AUTH) {
            return parameters.getAuthPolicy().isSimpleAuthAllowed()
                    ? AuthValidationResult.valid(method)
                    : AuthValidationResult.invalid(method,
                            AuthValidationReason.SIMPLE_AUTH_DISABLED);
        }

        Long timestamp = getRequestTimestamp();
        Long count = getRequestCount();
        boolean hasSignedTimestamp = timestamp != null && timestamp.longValue() != 0L;
        boolean hasSignedCount = count != null && count.longValue() != 0L;
        if ((method == AuthMethod.DATA_AUTH || method == AuthMethod.RECURSIVE_DATA_AUTH)
                && !hasSignedTimestamp && !hasSignedCount) {
            return AuthValidationResult.invalid(method,
                    AuthValidationReason.MISSING_REPLAY_FIELD);
        }
        if ((method == AuthMethod.TIMESTAMP_AUTH || method == AuthMethod.DATA_AUTH
                || method == AuthMethod.RECURSIVE_DATA_AUTH)
                && hasSignedTimestamp && parameters.getLastRequestTimestamp() != null
                && timestamp.longValue() <= parameters.getLastRequestTimestamp().longValue()) {
            return AuthValidationResult.invalid(method, AuthValidationReason.TIMESTAMP_REPLAY);
        }
        if ((method == AuthMethod.COUNTER_AUTH || method == AuthMethod.DATA_AUTH
                || method == AuthMethod.RECURSIVE_DATA_AUTH)
                && hasSignedCount && parameters.getLastRequestCount() != null
                && count.longValue() <= parameters.getLastRequestCount().longValue()) {
            return AuthValidationResult.invalid(method,
                    AuthValidationReason.REQUEST_COUNT_REPLAY);
        }
        return AuthValidationResult.valid(method);
    }

    public boolean isAuthValid() { return validateAuth().isValid(); }

    /**
     * Expands condensed data and historical samples into named fields, removes authentication data,
     * and resolves fixed, relative, or explicit sample timestamps to epoch seconds.
     *
     * @return immutable request data using expanded field names and absolute timestamps
     */
    public Map<String, Object> getSimpleMetrics() {
        LinkedHashMap<String, Object> simple = new LinkedHashMap<String, Object>(
                MetricsJsonCodec.copyObject(request));
        simple.remove("auth");
        simple.put("data", simpleData());
        simple.put("historical_data", fillHistoricalTimestamps(simpleHistoricalData()));
        return MetricsJsonCodec.immutableObject(simple);
    }

    public Long getDataTimestamp() {
        Long collection = optionalLong(request.get("data_collection_timestamp"),
                "data_collection_timestamp");
        return collection == null ? Long.valueOf(effectiveTimestamp) : collection;
    }

    public Long getRequestTimestamp() {
        return optionalLong(request.get("timestamp"), "timestamp");
    }

    public Long getRequestCount() {
        return optionalLong(request.get("request_count"), "request_count");
    }

    public Long getTokenCount() {
        return optionalLong(simpleData().get("token_count"), "token_count");
    }

    public boolean expectsTokenAnswer() { return getTokenCount() != null; }

    public boolean expectsTimeAnswer() {
        Map<String, Object> data = simpleData();
        return Boolean.TRUE.equals(data.get("active_until_timestamp_requested"))
                || Boolean.TRUE.equals(data.get("active_seconds_left_requested"));
    }

    /**
     * Adds tokens to the response using a defensive copy. Token strings retain leading zeroes.
     *
     * @param tokens token strings to return to the device
     */
    public void addTokensToAnswer(List<String> tokens) {
        if (tokens == null) throw new IllegalArgumentException("tokens are required");
        List<Object> copy = new ArrayList<Object>();
        for (String token : tokens) {
            if (token == null) throw new IllegalArgumentException("token list cannot contain null");
            copy.add(token);
        }
        response.put("token_list", copy);
    }

    /**
     * Adds every time representation requested by the device.
     *
     * <p>A {@code null} expiration encodes zero. Relative seconds are calculated against the
     * handler clock and clamped at zero for expired service.</p>
     *
     * @param expiration absolute service expiration, or {@code null} for inactive service
     * @throws IllegalStateException if the request did not ask for either time representation
     */
    public void addTimeToAnswer(Instant expiration) {
        Map<String, Object> data = simpleData();
        boolean absolute = Boolean.TRUE.equals(data.get("active_until_timestamp_requested"));
        boolean relative = Boolean.TRUE.equals(data.get("active_seconds_left_requested"));
        if (!absolute && !relative) throw new IllegalStateException("No time requested");
        if (absolute) {
            long epoch = expiration == null ? 0L : Math.max(0L, expiration.getEpochSecond());
            response.put("active_until_timestamp", BigInteger.valueOf(epoch));
        }
        if (relative) {
            long seconds = expiration == null ? 0L
                    : Math.max(0L, Duration.between(clock.instant(), expiration).getSeconds());
            response.put("active_seconds_left", BigInteger.valueOf(seconds));
        }
    }

    public void addSettingsToAnswer(Map<String, Object> settings) {
        mergeObject("settings", settings);
    }

    public void addExtraDataToAnswer(Map<String, Object> extraData) {
        mergeObject("extra_data", extraData);
    }

    public void addNewBaseUrlToAnswer(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        Map<String, Object> setting = new LinkedHashMap<String, Object>();
        setting.put("base_url", baseUrl);
        addSettingsToAnswer(setting);
    }

    /**
     * Produces the condensed response map and appends Data Auth when a device key is available.
     * The returned map is detached from the handler's mutable response state.
     *
     * @return immutable condensed response map
     */
    public Map<String, Object> buildAnswerMap() {
        LinkedHashMap<String, Object> answer = new LinkedHashMap<String, Object>(
                MetricsJsonCodec.copyObject(response));
        if (parameters.getSecretKey() != null) {
            answer.put("auth", MetricsAuthentication.signResponse(answer,
                    parameters.getSecretKey(), getDeviceSerial(), getRequestTimestamp(),
                    getRequestCount()));
        }
        return MetricsJsonCodec.immutableObject(MetricsJsonCodec.condenseTopLevel(answer));
    }

    /** Returns the signed response as deterministic compact JSON. */
    public String buildAnswerPayload() { return MetricsJsonCodec.toJson(buildAnswerMap()); }

    private Map<String, Object> simpleData() {
        Object raw = request.get("data");
        if (raw == null) return Collections.emptyMap();
        if (raw instanceof Map) return MetricsJsonCodec.copyObject((Map<?, ?>) raw);
        if (!(raw instanceof List)) throw new MetricsException("Metrics data must be object or array");
        requireDataFormat("data");
        List<?> values = (List<?>) raw;
        List<String> order = dataFormat.getDataOrder();
        if (order.isEmpty()) throw new MetricsException("Data Format does not contain data_order");
        if (values.size() > order.size()) {
            throw new MetricsException("Additional values not present in data format");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < order.size(); i++) {
            result.put(order.get(i), i < values.size()
                    ? MetricsJsonCodec.copyValue(values.get(i)) : null);
        }
        return MetricsJsonCodec.expandTopLevel(result);
    }

    private List<Map<String, Object>> simpleHistoricalData() {
        Object raw = request.get("historical_data");
        if (raw == null) return new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) {
            throw new MetricsException("historical_data must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object step : (List<?>) raw) {
            if (step instanceof List) {
                requireDataFormat("historical data");
                List<String> order = dataFormat.getHistoricalDataOrder();
                if (order.isEmpty()) {
                    throw new MetricsException(
                            "Data Format does not contain historical_data_order");
                }
                List<?> values = (List<?>) step;
                if (values.size() > order.size()) {
                    throw new MetricsException(
                            "Additional values not present in historical data format");
                }
                LinkedHashMap<String, Object> mapped = new LinkedHashMap<String, Object>();
                for (int i = 0; i < values.size(); i++) {
                    mapped.put(order.get(i), MetricsJsonCodec.copyValue(values.get(i)));
                }
                result.add(mapped);
            } else if (step instanceof Map) {
                Map<String, Object> source = MetricsJsonCodec.copyObject((Map<?, ?>) step);
                LinkedHashMap<String, Object> mapped = new LinkedHashMap<String, Object>();
                for (Map.Entry<String, Object> entry : source.entrySet()) {
                    String key = entry.getKey();
                    if (isDigits(key)) {
                        requireDataFormat("historical data");
                        int index = Integer.parseInt(key);
                        if (index >= dataFormat.getHistoricalDataOrder().size()) {
                            throw new MetricsException("Historical data index is outside data format");
                        }
                        key = dataFormat.getHistoricalDataOrder().get(index);
                    }
                    mapped.put(key, entry.getValue());
                }
                result.add(mapped);
            } else {
                throw new MetricsException("Invalid historical data step type");
            }
        }
        return result;
    }

    private List<Object> fillHistoricalTimestamps(List<Map<String, Object>> history) {
        long last = getDataTimestamp().longValue();
        List<Object> result = new ArrayList<Object>();
        for (int index = 0; index < history.size(); index++) {
            LinkedHashMap<String, Object> step = new LinkedHashMap<String, Object>(history.get(index));
            if (step.containsKey("relative_time") && step.get("relative_time") != null) {
                last = Math.addExact(last, requiredLong(step.get("relative_time"), "relative_time"));
                step.put("timestamp", BigInteger.valueOf(last));
                step.remove("relative_time");
            } else if (step.get("timestamp") != null) {
                last = requiredLong(step.get("timestamp"), "timestamp");
            } else {
                if (index > 0) {
                    requireDataFormat("historical interval");
                    Long interval = dataFormat.getHistoricalDataInterval();
                    if (interval == null) {
                        throw new MetricsException(
                                "Historical data requires timestamp when no interval is defined");
                    }
                    last = Math.addExact(last, interval.longValue());
                }
                step.put("timestamp", BigInteger.valueOf(last));
            }
            result.add(step);
        }
        return result;
    }

    private void mergeObject(String key, Map<String, Object> additions) {
        Map<String, Object> merged = response.get(key) instanceof Map
                ? MetricsJsonCodec.copyObject((Map<?, ?>) response.get(key))
                : new LinkedHashMap<String, Object>();
        merged.putAll(MetricsJsonCodec.copyObject(additions));
        response.put(key, merged);
    }

    private void requireDataFormat(String purpose) {
        if (dataFormat == null) throw new MetricsException("Data Format is required for " + purpose);
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static Long optionalLong(Object value, String name) {
        return value == null ? null : Long.valueOf(requiredLong(value, name));
    }

    private static long requiredLong(Object value, String name) {
        try {
            if (value instanceof BigInteger) return ((BigInteger) value).longValueExact();
            if (value instanceof BigDecimal) return ((BigDecimal) value).longValueExact();
            if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long) return ((Number) value).longValue();
        } catch (ArithmeticException exception) {
            throw new MetricsException(name + " is outside long range", exception);
        }
        throw new MetricsException(name + " must be an integer");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObject(Object value) {
        return (Map<String, Object>) value;
    }
}
