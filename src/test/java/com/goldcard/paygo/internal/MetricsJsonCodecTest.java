package com.goldcard.paygo.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.goldcard.paygo.metrics.MetricsException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class MetricsJsonCodecTest {
    @Test
    public void writesStableCompactPythonStyleJson() {
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        object.put("z", "中文\n");
        object.put("a", BigInteger.valueOf(3));
        object.put("decimal", new BigDecimal("12.300"));
        object.put("decimalInteger", new BigDecimal("1.0"));
        object.put("empty", null);
        assertEquals("{\"z\":\"\\u4e2d\\u6587\\n\",\"a\":3,\"decimal\":12.3,"
                        + "\"decimalInteger\":1.0,\"empty\":null}",
                MetricsJsonCodec.toJson(object));
    }

    @Test
    public void parsesAndPreservesFieldOrder() {
        Map<String, Object> parsed = MetricsJsonCodec.parseObject("{\"b\":2,\"a\":1}");
        assertEquals("{\"b\":2,\"a\":1}", MetricsJsonCodec.toJson(parsed));
    }

    @Test
    public void defensivelyCopiesNestedValues() {
        List<Object> nested = new ArrayList<Object>();
        nested.add("first");
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("nested", nested);
        Map<String, Object> copy = MetricsJsonCodec.immutableObject(source);
        nested.add("second");
        assertEquals("{\"nested\":[\"first\"]}", MetricsJsonCodec.toJson(copy));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteNumbers() {
        MetricsJsonCodec.toJson(Double.NaN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPojoValues() {
        MetricsJsonCodec.toJson(new Object());
    }

    @Test(expected = MetricsException.class)
    public void rejectsArrayRootPayload() {
        MetricsJsonCodec.parseObject("[]");
    }

    @Test(expected = MetricsException.class)
    public void rejectsSingleQuotedJson() {
        MetricsJsonCodec.parseObject("{'value':1}");
    }

    @Test
    public void malformedJsonPreservesParserCause() {
        try {
            MetricsJsonCodec.parseObject("{\"value\":1");
        } catch (MetricsException exception) {
            assertNotNull(exception.getCause());
            return;
        }
        throw new AssertionError("Expected MetricsException");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonStringObjectKey() {
        Map<Object, Object> object = new LinkedHashMap<Object, Object>();
        object.put(Integer.valueOf(1), "value");
        MetricsJsonCodec.toJson(object);
    }

    @Test
    public void escapesSupplementaryUnicodeAsSurrogatePair() {
        assertTrue(MetricsJsonCodec.toJson("\ud83d\ude00").contains("\\ud83d\\ude00"));
    }
}
