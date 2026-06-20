package com.etiya.replayfix.model;

import java.io.File;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.BaseStream;

final class JsonSafeValueSanitizer {

    private JsonSafeValueSanitizer() {
    }

    static Map<String, Object> safeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return safeMap(values, new IdentityHashMap<>());
    }

    static <T> List<T> safeList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }

    private static Object safeValue(
            Object value,
            IdentityHashMap<Object, Boolean> visited
    ) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte
                || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) {
            return value;
        }
        if (value instanceof Float floatValue) {
            return Float.isFinite(floatValue) ? floatValue : null;
        }
        if (value instanceof Double doubleValue) {
            return Double.isFinite(doubleValue) ? doubleValue : null;
        }
        if (value instanceof UUID || value instanceof Path || value instanceof File) {
            return value.toString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Optional<?> optional) {
            return optional
                    .map(optionalValue -> safeValue(optionalValue, visited))
                    .orElse(null);
        }
        if (value instanceof Pattern pattern) {
            return pattern.pattern();
        }
        if (value instanceof Matcher matcher) {
            return matcher.pattern().pattern();
        }
        if (value instanceof Throwable throwable) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("exceptionClass", throwable.getClass().getName());
            error.put("message", throwable.getMessage());
            return error;
        }
        if (value instanceof Process || value instanceof ProcessBuilder) {
            return value.getClass().getName();
        }
        if (value instanceof BaseStream<?, ?>) {
            return value.getClass().getName();
        }
        if (value instanceof Map<?, ?> map) {
            if (visited.containsKey(value)) {
                return "[cyclic-reference]";
            }
            visited.put(value, Boolean.TRUE);
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null
                        ? "null"
                        : entry.getKey().toString();
                sanitized.put(key, safeValue(entry.getValue(), visited));
            }
            visited.remove(value);
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            if (visited.containsKey(value)) {
                return List.of("[cyclic-reference]");
            }
            visited.put(value, Boolean.TRUE);
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(safeValue(item, visited));
            }
            visited.remove(value);
            return sanitized;
        }
        if (value.getClass().isArray()) {
            if (visited.containsKey(value)) {
                return List.of("[cyclic-reference]");
            }
            visited.put(value, Boolean.TRUE);
            int length = Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                sanitized.add(safeValue(Array.get(value, index), visited));
            }
            visited.remove(value);
            return sanitized;
        }
        return value.toString();
    }

    private static Map<String, Object> safeMap(
            Map<String, Object> values,
            IdentityHashMap<Object, Boolean> visited
    ) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "null" : entry.getKey();
            sanitized.put(key, safeValue(entry.getValue(), visited));
        }
        return sanitized;
    }
}
