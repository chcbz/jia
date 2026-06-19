package cn.jia.core.security;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
public final class SensitiveDataSanitizer {
    private static final String REDACTED_TEXT_VALUE = "******";

    private SensitiveDataSanitizer() {
    }

    public static Object sanitize(Object source) {
        return sanitize(source, SensitiveSanitizeConfig.defaults());
    }

    public static Object sanitize(Object source, SensitiveSanitizeConfig config) {
        SensitiveSanitizeConfig effectiveConfig = config == null ? SensitiveSanitizeConfig.defaults() : config;
        if (!effectiveConfig.isEnabled() || source == null) {
            return source;
        }
        try {
            return sanitizeValue(source, effectiveConfig, new IdentityHashMap<>(), 0);
        } catch (RuntimeException e) {
            if (effectiveConfig.isFailOpen()) {
                log.warn("Failed to sanitize response, returning original object", e);
                return source;
            }
            throw e;
        }
    }

    private static Object sanitizeValue(Object value, SensitiveSanitizeConfig config,
                                        IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return redactSensitiveText(text.toString(), config);
        }
        if (isSimpleValue(value)) {
            return value;
        }
        if (depth >= config.getMaxDepth()) {
            return null;
        }
        if (visited.containsKey(value)) {
            return "[Circular]";
        }
        visited.put(value, Boolean.TRUE);
        try {
            if (value instanceof Map<?, ?> map) {
                return sanitizeMap(map, config, visited, depth);
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> result = new ArrayList<>();
                for (Object item : iterable) {
                    result.add(sanitizeValue(item, config, visited, depth + 1));
                }
                return result;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> result = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    result.add(sanitizeValue(Array.get(value, i), config, visited, depth + 1));
                }
                return result;
            }
            if (value.getClass().getName().startsWith("java.")) {
                return value;
            }
            return sanitizeBean(value, config, visited, depth);
        } finally {
            visited.remove(value);
        }
    }

    private static Map<Object, Object> sanitizeMap(Map<?, ?> source, SensitiveSanitizeConfig config,
                                                   IdentityHashMap<Object, Boolean> visited, int depth) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String fieldName = String.valueOf(entry.getKey());
            FieldDecision decision = decide(fieldName, null, config);
            if (decision.strategy == SensitiveStrategy.DROP && !config.isAuditOnly()) {
                continue;
            }
            result.put(entry.getKey(), applyDecision(entry.getValue(), decision, config, visited, depth));
        }
        return result;
    }

    private static Map<String, Object> sanitizeBean(Object source, SensitiveSanitizeConfig config,
                                                    IdentityHashMap<Object, Boolean> visited, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : fieldsOf(source.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            FieldDecision decision = decide(field.getName(), field, config);
            if (decision.strategy == SensitiveStrategy.DROP && !config.isAuditOnly()) {
                continue;
            }
            try {
                result.put(field.getName(), applyDecision(field.get(source), decision, config, visited, depth));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access field " + field.getName(), e);
            }
        }
        return result;
    }

    private static Object applyDecision(Object value, FieldDecision decision, SensitiveSanitizeConfig config,
                                        IdentityHashMap<Object, Boolean> visited, int depth) {
        if (config.isAuditOnly() || decision.strategy == SensitiveStrategy.KEEP) {
            return sanitizeValue(value, config, visited, depth + 1);
        }
        if (decision.strategy == SensitiveStrategy.NULL) {
            return null;
        }
        if (decision.strategy == SensitiveStrategy.MASK) {
            return mask(decision.fieldName, value);
        }
        return sanitizeValue(value, config, visited, depth + 1);
    }

    private static FieldDecision decide(String fieldName, Field field, SensitiveSanitizeConfig config) {
        SensitiveField annotation = field == null ? null : field.getAnnotation(SensitiveField.class);
        if (annotation != null) {
            return new FieldDecision(fieldName, annotation.strategy());
        }
        if (config.isSecretField(fieldName)) {
            return new FieldDecision(fieldName, config.getSecretStrategy());
        }
        if (config.isMaskDisplayFields() && config.isMaskField(fieldName)) {
            return new FieldDecision(fieldName, SensitiveStrategy.MASK);
        }
        return new FieldDecision(fieldName, SensitiveStrategy.KEEP);
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(List.of(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static boolean isSimpleValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Date
                || value instanceof BigDecimal
                || value instanceof BigInteger
                || value instanceof TemporalAccessor;
    }

    private static Object mask(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        String normalized = fieldName == null ? "" : fieldName.toLowerCase();
        if (Set.of("phone", "mobile").contains(normalized) && text.length() >= 7) {
            return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
        }
        if (normalized.contains("email")) {
            int at = text.indexOf('@');
            if (at > 0) {
                return text.charAt(0) + "***" + text.substring(at);
            }
        }
        if (text.length() <= 4) {
            return "****";
        }
        return text.substring(0, 2) + "****" + text.substring(text.length() - 2);
    }

    private static String redactSensitiveText(String text, SensitiveSanitizeConfig config) {
        if (text == null || text.isEmpty() || config.isAuditOnly()) {
            return text;
        }
        String redacted = text;
        for (String patternText : config.getTextRedactionPatterns()) {
            if (patternText == null || patternText.isBlank()) {
                continue;
            }
            try {
                redacted = applyTextRedactionPattern(redacted, Pattern.compile(patternText, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                if (config.isFailOpen()) {
                    log.warn("Invalid sensitive text redaction pattern: {}", patternText, e);
                    continue;
                }
                throw e;
            }
        }
        return redacted;
    }

    private static String applyTextRedactionPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            if (matcher.groupCount() < 2) {
                continue;
            }
            String suffix = matcher.groupCount() >= 3 && matcher.group(3) != null ? matcher.group(3) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + REDACTED_TEXT_VALUE + suffix));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private record FieldDecision(String fieldName, SensitiveStrategy strategy) {
    }
}
