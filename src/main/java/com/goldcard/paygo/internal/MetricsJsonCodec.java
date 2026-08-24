package com.goldcard.paygo.internal;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.goldcard.paygo.metrics.MetricsException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON boundary and deterministic serializer used by Metrics authentication.
 *
 * <p>Only JSON-native values are accepted. Maps preserve insertion order, numbers are normalized,
 * and non-ASCII characters use Python-compatible Unicode escapes so identical logical payloads
 * produce identical signature bytes across supported languages.</p>
 *
 * @author dyzmj
 */
public final class MetricsJsonCodec {
    private static final Map<String, String> CONDENSED_KEYS;

    static {
        LinkedHashMap<String, String> keys = new LinkedHashMap<String, String>();
        keys.put("serial_number", "sn");
        keys.put("timestamp", "ts");
        keys.put("auth", "a");
        keys.put("request_count", "rc");
        keys.put("data_collection_timestamp", "dtc");
        keys.put("data_format_id", "df");
        keys.put("data_format", "dfo");
        keys.put("data", "d");
        keys.put("historical_data", "hd");
        keys.put("accessories", "acc");
        keys.put("token_list", "tkl");
        keys.put("active_until_timestamp", "auts");
        keys.put("active_seconds_left", "asl");
        keys.put("settings", "st");
        keys.put("extra_data", "ed");
        keys.put("token_count", "tc");
        keys.put("active_until_timestamp_requested", "autsr");
        keys.put("active_seconds_left_requested", "aslr");
        CONDENSED_KEYS = Collections.unmodifiableMap(keys);
    }

    private MetricsJsonCodec() {}

    /**
     * Parses a strict JSON object and recursively normalizes it into supported Java value types.
     * Parser failures are wrapped with their original cause for diagnostics.
     *
     * @param json JSON text whose root value must be an object
     * @return insertion-ordered, recursively normalized object
     * @throws MetricsException if parsing fails or the root value is not an object
     */
    public static Map<String, Object> parseObject(String json) {
        if (json == null) throw new MetricsException("Metrics payload is required");
        try {
            Object parsed = JSON.parse(json, JSONReader.Feature.DisableSingleQuote);
            if (!(parsed instanceof Map)) {
                throw new MetricsException("Metrics payload root must be a JSON object");
            }
            return copyObject((Map<?, ?>) parsed);
        } catch (MetricsException exception) {
            throw exception;
        } catch (JSONException exception) {
            throw new MetricsException("Invalid Metrics JSON", exception);
        } catch (RuntimeException exception) {
            throw new MetricsException("Invalid Metrics JSON value", exception);
        }
    }

    public static Map<String, Object> immutableObject(Map<?, ?> source) {
        return Collections.unmodifiableMap(copyObject(source));
    }

    public static List<Object> immutableArray(List<?> source) {
        return Collections.unmodifiableList(copyArray(source));
    }

    public static Map<String, Object> copyObject(Map<?, ?> source) {
        if (source == null) throw new IllegalArgumentException("JSON object is required");
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            result.put((String) entry.getKey(), normalize(entry.getValue()));
        }
        return result;
    }

    public static List<Object> copyArray(List<?> source) {
        if (source == null) throw new IllegalArgumentException("JSON array is required");
        ArrayList<Object> result = new ArrayList<Object>(source.size());
        for (Object value : source) result.add(normalize(value));
        return result;
    }

    public static Object copyValue(Object value) { return normalize(value); }

    public static Map<String, Object> condenseTopLevel(Map<String, ?> source) {
        return replaceTopLevelKeys(source, false);
    }

    public static Map<String, Object> expandTopLevel(Map<String, ?> source) {
        return replaceTopLevelKeys(source, true);
    }

    /**
     * Serializes a supported value without whitespace while preserving map insertion order.
     *
     * @param value JSON-compatible value to serialize
     * @return deterministic compact JSON
     * @throws IllegalArgumentException if the value contains an unsupported type or number
     */
    public static String toJson(Object value) {
        StringBuilder output = new StringBuilder();
        appendJson(output, normalize(value));
        return output.toString();
    }

    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigInteger) return value;
        if (value instanceof BigDecimal) {
            return normalizeDecimal((BigDecimal) value);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
            return normalizeDecimal(BigDecimal.valueOf(number));
        }
        if (value instanceof Map) return copyObject((Map<?, ?>) value);
        if (value instanceof List) return copyArray((List<?>) value);
        throw new IllegalArgumentException("Unsupported JSON value type: "
                + value.getClass().getName());
    }

    private static BigDecimal normalizeDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (value.scale() > 0 && normalized.scale() <= 0) {
            return normalized.setScale(1);
        }
        return normalized;
    }

    private static Map<String, Object> replaceTopLevelKeys(Map<String, ?> source,
                                                           boolean expand) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String replacement = null;
            if (expand) {
                for (Map.Entry<String, String> key : CONDENSED_KEYS.entrySet()) {
                    if (key.getValue().equals(entry.getKey())) {
                        replacement = key.getKey();
                        break;
                    }
                }
            } else {
                replacement = CONDENSED_KEYS.get(entry.getKey());
            }
            result.put(replacement == null ? entry.getKey() : replacement,
                    normalize(entry.getValue()));
        }
        return result;
    }

    private static void appendJson(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            appendString(output, (String) value);
        } else if (value instanceof Boolean || value instanceof BigInteger) {
            output.append(value.toString().toLowerCase());
        } else if (value instanceof BigDecimal) {
            output.append(((BigDecimal) value).toPlainString());
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) output.append(',');
                first = false;
                appendString(output, (String) entry.getKey());
                output.append(':');
                appendJson(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof List) {
            output.append('[');
            List<?> list = (List<?>) value;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) output.append(',');
                appendJson(output, list.get(i));
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported normalized JSON value");
        }
    }

    private static void appendString(StringBuilder output, String value) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20 || character > 0x7e) {
                        output.append("\\u");
                        String hex = Integer.toHexString(character);
                        for (int padding = hex.length(); padding < 4; padding++) output.append('0');
                        output.append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }
}
