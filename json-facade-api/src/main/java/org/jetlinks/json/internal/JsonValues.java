package org.jetlinks.json.internal;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonValue;

/** Shared JDK implementation available to providers. Backing values never escape through public export. */
public final class JsonValues {
    private JsonValues() {
    }

    public static JsonValue nullValue() {
        return new Scalar(null, false);
    }

    public static JsonValue missingValue() {
        return new Scalar(null, true);
    }

    public static JsonObject object() {
        return new ObjectValue(new LinkedHashMap<>());
    }

    public static JsonArray array() {
        return new ArrayValue(new ArrayList<>());
    }

    public static JsonValue of(Object value) {
        return value instanceof JsonValue node ? node : wrap(copy(value));
    }

    /** Borrow only for immediate synchronous binding. Providers must not expose or retain this graph. */
    public static Object bindingValue(Object value) {
        if (value instanceof Scalar node) {
            node.checkPresent();
            return node.value;
        }
        return value instanceof JsonValue node ? node.toJavaValue() : value;
    }

    private static JsonValue wrap(Object value) {
        if (value == null) {
            return JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            return new ObjectValue(map);
        }
        if (value instanceof List<?> list) {
            return new ArrayValue(list);
        }
        return new Scalar(value, false);
    }

    private static Object copy(Object value) {
        if (value instanceof JsonValue node) {
            return node.toJavaValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("JSON object keys must be Strings");
                }
                result.put(name, copy(child));
            });
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>(collection.size());
            collection.forEach(child -> result.add(copy(child)));
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(copy(Array.get(value, i)));
            }
            return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Use JsonMapper.toTree for Java objects");
    }

    private static class Scalar implements JsonValue {
        private final Object value;
        private final boolean missing;

        Scalar(Object value, boolean missing) {
            this.value = value;
            this.missing = missing;
        }

        void checkPresent() {
            if (missing) {
                throw new IllegalStateException("Missing JSON is not a value");
            }
        }

        @Override
        public boolean isNull() {
            return !missing && value == null;
        }

        @Override
        public boolean isMissing() {
            return missing;
        }

        @Override
        public Object toJavaValue() {
            checkPresent();
            return copy(value);
        }

        @Override
        public JsonValue get(String name) {
            return JsonValue.MISSING;
        }

        @Override
        public JsonValue get(int index) {
            return JsonValue.MISSING;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public JsonValue deepCopy() {
            return missing ? JsonValue.MISSING : wrap(copy(value));
        }
    }

    private static final class ObjectValue extends Scalar implements JsonObject {
        private final Map<String, Object> values;

        @SuppressWarnings("unchecked")
        ObjectValue(Map<?, ?> values) {
            super(values, false);
            this.values = (Map<String, Object>) values;
        }

        @Override
        public JsonValue get(String name) {
            return values.containsKey(name) ? wrap(values.get(name)) : JsonValue.MISSING;
        }

        @Override
        public JsonObject put(String name, Object value) {
            values.put(Objects.requireNonNull(name, "name"), copy(value));
            return this;
        }

        @Override
        public JsonObject remove(String name) {
            values.remove(name);
            return this;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public JsonObject deepCopy() {
            return (JsonObject) super.deepCopy();
        }
    }

    private static final class ArrayValue extends Scalar implements JsonArray {
        private final List<Object> values;

        @SuppressWarnings("unchecked")
        ArrayValue(List<?> values) {
            super(values, false);
            this.values = (List<Object>) values;
        }

        @Override
        public JsonValue get(int index) {
            return index >= 0 && index < values.size() ? wrap(values.get(index)) : JsonValue.MISSING;
        }

        @Override
        public JsonArray add(Object value) {
            values.add(copy(value));
            return this;
        }

        @Override
        public JsonArray set(int index, Object value) {
            values.set(index, copy(value));
            return this;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public JsonArray deepCopy() {
            return (JsonArray) super.deepCopy();
        }
    }
}
