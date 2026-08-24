package com.goldcard.paygo.internal;

import com.goldcard.paygo.metrics.AuthMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Creates protocol-compatible SipHash signatures for Metrics requests and responses.
 *
 * @author dyzmj
 */
public final class MetricsAuthentication {
    private MetricsAuthentication() {}

    /**
     * Signs the exact fields defined by the selected authentication method. The two-character method
     * prefix is included in the returned wire value but is not part of the SipHash input.
     *
     * @param data canonical request fields in protocol order
     * @param method authentication method that selects the signed fields
     * @param secretKey shared 128-bit hexadecimal secret key
     * @return method prefix followed by the unsigned hexadecimal SipHash signature
     */
    public static String signRequest(Map<String, Object> data, AuthMethod method,
                                     String secretKey) {
        if (data == null || method == null) {
            throw new IllegalArgumentException("data and authentication method are required");
        }
        String serial = requiredString(data, "serial_number");
        String signature;
        switch (method) {
            case SIMPLE_AUTH:
                signature = hashString(serial, secretKey);
                break;
            case TIMESTAMP_AUTH:
                Object timestamp = requiredNonZero(data, "timestamp",
                        "Timestamp is required for Timestamp Auth");
                signature = hashString(serial + scalarString(timestamp), secretKey);
                break;
            case COUNTER_AUTH:
                Object count = requiredNonZero(data, "request_count",
                        "Request Count is required for Counter Auth");
                signature = hashString(serial + scalarString(count), secretKey);
                break;
            case DATA_AUTH:
                signature = hashString(flatPayload(data, serial), secretKey);
                break;
            case RECURSIVE_DATA_AUTH:
                signature = recursiveSignature(data, serial, secretKey);
                break;
            default:
                throw new IllegalArgumentException("Unsupported authentication method");
        }
        return method.getCode() + signature;
    }

    /**
     * Signs a server response using the originating device identity and non-zero replay fields,
     * followed by response sections in protocol order.
     *
     * @param data canonical response fields in protocol order
     * @param secretKey shared 128-bit hexadecimal secret key
     * @param serialNumber responding device serial number
     * @param timestamp timestamp copied from the originating request, if present
     * @param requestCount counter copied from the originating request, if present
     * @return Data Auth prefix followed by the unsigned hexadecimal SipHash signature
     */
    public static String signResponse(Map<String, Object> data, String secretKey,
                                      String serialNumber, Long timestamp,
                                      Long requestCount) {
        if (serialNumber == null) throw new IllegalArgumentException("serialNumber is required");
        StringBuilder payload = new StringBuilder(serialNumber);
        appendNonZero(payload, timestamp);
        appendNonZero(payload, requestCount);
        appendNonZero(payload, data.get("active_until_timestamp"));
        appendNonZero(payload, data.get("active_seconds_left"));
        appendJsonIfNonEmpty(payload, data.get("token_list"));
        appendJsonIfNonEmpty(payload, data.get("settings"));
        appendJsonIfNonEmpty(payload, data.get("extra_data"));
        return AuthMethod.DATA_AUTH.getCode() + hashString(payload.toString(), secretKey);
    }

    /**
     * Hashes UTF-8 text with a 128-bit hexadecimal key and returns unsigned lower-case hexadecimal
     * without leading zero padding, matching the Python and JavaScript implementations.
     *
     * @param input exact UTF-8 text to hash
     * @param secretKey 128-bit hexadecimal SipHash key
     * @return unsigned lower-case hexadecimal SipHash-2-4 result
     */
    public static String hashString(String input, String secretKey) {
        byte[] key = HexKeys.decodeSecretKey(secretKey);
        long hash = SipHash24.hash(key, input.getBytes(StandardCharsets.UTF_8));
        return Long.toUnsignedString(hash, 16);
    }

    private static String flatPayload(Map<String, Object> data, String serial) {
        StringBuilder payload = new StringBuilder(serial);
        appendNonZero(payload, data.get("timestamp"));
        appendNonZero(payload, data.get("request_count"));
        appendJsonIfNonEmpty(payload, data.get("data"));
        appendJsonIfNonEmpty(payload, data.get("historical_data"));
        return payload.toString();
    }

    private static String recursiveSignature(Map<String, Object> data, String serial,
                                             String secretKey) {
        String payload = hashString(serial, secretKey);
        if (isNonZero(data.get("timestamp"))) {
            payload = hashString(payload + scalarString(data.get("timestamp")), secretKey);
        }
        if (isNonZero(data.get("request_count"))) {
            payload = hashString(payload + scalarString(data.get("request_count")), secretKey);
        }
        Object current = data.get("data");
        payload = hashString(payload + MetricsJsonCodec.toJson(
                current == null ? java.util.Collections.emptyList() : current), secretKey);
        Object history = data.get("historical_data");
        if (history instanceof List) {
            for (Object step : (List<?>) history) {
                payload = hashString(payload + MetricsJsonCodec.toJson(step), secretKey);
            }
        }
        return payload;
    }

    private static String requiredString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return (String) value;
    }

    private static Object requiredNonZero(Map<String, Object> data, String key,
                                          String message) {
        Object value = data.get(key);
        if (!isNonZero(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private static void appendNonZero(StringBuilder target, Object value) {
        if (isNonZero(value)) target.append(scalarString(value));
    }

    private static void appendJsonIfNonEmpty(StringBuilder target, Object value) {
        if (value instanceof Map && !((Map<?, ?>) value).isEmpty()) {
            target.append(MetricsJsonCodec.toJson(value));
        } else if (value instanceof List && !((List<?>) value).isEmpty()) {
            target.append(MetricsJsonCodec.toJson(value));
        }
    }

    private static boolean isNonZero(Object value) {
        if (value == null) return false;
        if (value instanceof java.math.BigInteger) {
            return ((java.math.BigInteger) value).signum() != 0;
        }
        if (value instanceof java.math.BigDecimal) {
            return ((java.math.BigDecimal) value).signum() != 0;
        }
        if (value instanceof Number) return ((Number) value).doubleValue() != 0d;
        return true;
    }

    private static String scalarString(Object value) {
        return value.toString();
    }
}
