package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical JSON v1: UTF-8, lexical object keys, preserved array order, integral numbers only. */
public final class CanonicalContextJson {
    private CanonicalContextJson() { }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            out.append(JsonUtil.toJson(text));
        } else if (value instanceof Boolean bool) {
            out.append(bool);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof BigDecimal decimal && decimal.scale() <= 0) {
            out.append(decimal.toBigIntegerExact());
        } else if (value instanceof Number) {
            throw new IllegalArgumentException("Canonical context forbids floating-point numbers");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            List<Map.Entry<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Canonical context object keys must be strings");
                }
                entries.add(new java.util.AbstractMap.SimpleImmutableEntry<>(key, entry.getValue()));
            }
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) out.append(',');
                append(out, entries.get(i).getKey());
                out.append(':');
                append(out, entries.get(i).getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                append(out, item);
                first = false;
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported canonical context value: " + value.getClass());
        }
    }
}
