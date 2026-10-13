package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Canonical JSON: UTF-8, lexical keys, preserved array order; portable JSON numbers for action-v4. */
public final class CanonicalContextJson {
    private CanonicalContextJson() { }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void requireSafeInteger(BigInteger integer) {
        if (integer.abs().compareTo(BigInteger.valueOf(9007199254740991L)) > 0)
            throw new IllegalArgumentException("Canonical context forbids unsafe integers; use strings");
    }

    // Search significant digits against the exact binary value, selecting the nearest decimal
    // (ties to even). Unlike Java/Jackson formatting this permits 5e-324 for the minimum subnormal.
    private static BigDecimal shortestDecimal(double value) {
        if (value == 0) return BigDecimal.ZERO;
        BigDecimal exact = new BigDecimal(value);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal rounded = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.doubleToLongBits(rounded.doubleValue()) == Double.doubleToLongBits(value))
                return rounded.stripTrailingZeros();
        }
        throw new IllegalArgumentException("No portable decimal representation");
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
            BigInteger integer = new BigInteger(value.toString());
            requireSafeInteger(integer);
            out.append(integer);
        } else if (value instanceof BigDecimal decimal && decimal.scale() <= 0) {
            BigInteger integer = decimal.toBigIntegerExact();
            requireSafeInteger(integer);
            out.append(integer);
        } else if (value instanceof Number number) {
            double n=number.doubleValue();
            if(!Double.isFinite(n)||(n==Math.rint(n)&&Math.abs(n)>9007199254740991d))
                throw new IllegalArgumentException("Canonical context forbids non-portable numbers");
            BigDecimal decimal=shortestDecimal(n);
            if(decimal.signum()==0)out.append('0');
            else if(Math.abs(n)>=1e-6&&Math.abs(n)<1e21)out.append(decimal.toPlainString());
            else {String text=decimal.toString().replace("E","e");out.append(text);}

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
