package com.goldcard.paygo.metrics;

import com.goldcard.paygo.internal.MetricsJsonCodec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MetricsDataFormat {
    private final Map<String, Object> definition;
    private final Object id;
    private final List<String> dataOrder;
    private final List<String> historicalDataOrder;
    private final Long historicalDataInterval;

    private MetricsDataFormat(Map<String, Object> definition) {
        this.definition = MetricsJsonCodec.immutableObject(definition);
        this.id = MetricsJsonCodec.copyValue(this.definition.get("id"));
        this.dataOrder = stringList(this.definition.get("data_order"), "data_order");
        this.historicalDataOrder = stringList(
                this.definition.get("historical_data_order"), "historical_data_order");
        Object interval = this.definition.get("historical_data_interval");
        if (interval == null) {
            this.historicalDataInterval = null;
        } else if (interval instanceof BigInteger) {
            try {
                this.historicalDataInterval = Long.valueOf(((BigInteger) interval).longValueExact());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("historical_data_interval is outside long range", exception);
            }
            if (historicalDataInterval.longValue() <= 0) {
                throw new IllegalArgumentException("historical_data_interval must be positive");
            }
        } else {
            throw new IllegalArgumentException("historical_data_interval must be an integer");
        }
    }

    public static MetricsDataFormat of(Map<String, ?> definition) {
        if (definition == null) throw new IllegalArgumentException("data format is required");
        return new MetricsDataFormat(MetricsJsonCodec.copyObject(definition));
    }

    public static MetricsDataFormat parse(String json) {
        return new MetricsDataFormat(MetricsJsonCodec.parseObject(json));
    }

    public Map<String, Object> getDefinition() {
        return MetricsJsonCodec.immutableObject(definition);
    }

    public Object getId() { return MetricsJsonCodec.copyValue(id); }
    public List<String> getDataOrder() { return dataOrder; }
    public List<String> getHistoricalDataOrder() { return historicalDataOrder; }
    public Long getHistoricalDataInterval() { return historicalDataInterval; }

    private static List<String> stringList(Object value, String name) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new IllegalArgumentException(name + " must be an array");
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String) || ((String) item).isEmpty()) {
                throw new IllegalArgumentException(name + " must contain non-empty strings");
            }
            result.add((String) item);
        }
        return Collections.unmodifiableList(result);
    }
}
