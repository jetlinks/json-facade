package org.jetlinks.json.internal;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonFailure;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;

/** Shared JDK tree. Binding borrows backing values synchronously; views retain no ancestor data. */
public final class JsonValues {
    // Exact classes: Number and even BigDecimal/BigInteger subclasses may contain mutable state.
    private static final Set<Class<?>> IMMUTABLE_NUMBERS = Set.of(Byte.class, Short.class, Integer.class,
            Long.class, Float.class, Double.class, BigInteger.class, BigDecimal.class);
    public static final JsonType<Integer> INT = JsonType.of(int.class);
    public static final JsonType<Integer> INTEGER = JsonType.of(Integer.class);
    public static final JsonType<Long> LONG = JsonType.of(long.class);
    public static final JsonType<Double> DOUBLE = JsonType.of(double.class);
    public static final JsonType<Float> FLOAT = JsonType.of(float.class);
    public static final JsonType<Short> SHORT = JsonType.of(short.class);
    public static final JsonType<Byte> BYTE = JsonType.of(byte.class);
    public static final JsonType<Boolean> BOOLEAN = JsonType.of(boolean.class);
    public static final JsonType<String> STRING = JsonType.of(String.class);
    public static final JsonType<java.math.BigDecimal> BIG_DECIMAL = JsonType.of(java.math.BigDecimal.class);
    public static final JsonType<java.math.BigInteger> BIG_INTEGER = JsonType.of(java.math.BigInteger.class);

    private JsonValues() {
    }

    public static JsonValue nullValue() {
        return new BaseValue(null, false, null, JsonPath.ROOT);
    }

    public static JsonValue missingValue() {
        return new BaseValue(null, true, null, JsonPath.ROOT);
    }

    public static JsonObject object() {
        return object(null);
    }

    public static JsonObject object(JsonMapper mapper) {
        return new ObjectValue(new LinkedHashMap<>(), mapper, JsonPath.ROOT, false);
    }

    public static JsonArray array() {
        return array(null);
    }

    public static JsonArray array(JsonMapper mapper) {
        return new ArrayValue(new ArrayList<>(), mapper, JsonPath.ROOT, false);
    }

    /** Borrow JSON-only storage without walking it; nested access preserves read-only ownership. */
    public static JsonObject borrowObject(Map<String, ?> values, JsonMapper mapper) {
        return new ObjectValue(Objects.requireNonNull(values, "values"), mapper, JsonPath.ROOT, true);
    }

    /** List counterpart of borrowObject; copying remains explicit at export/deepCopy boundaries. */
    public static JsonArray borrowArray(List<?> values, JsonMapper mapper) {
        return new ArrayValue(Objects.requireNonNull(values, "values"), mapper, JsonPath.ROOT, true);
    }

    public static JsonValue of(Object value) {
        return value instanceof JsonValue node ? node : wrap(copy(value), null, JsonPath.ROOT);
    }

    /** Copy a binding result and attach its configured mapper, including for nested views. */
    public static JsonValue of(Object value, JsonMapper mapper) {
        return wrap(copy(value), Objects.requireNonNull(mapper, "mapper"), JsonPath.ROOT);
    }

    /** Borrow only for immediate synchronous binding. Providers must not expose or retain this graph. */
    public static Object bindingValue(Object value) {
        if (value instanceof BaseValue node) {
            node.checkPresent();
            return node.value;
        }
        return value instanceof JsonValue node ? node.toJavaValue() : value;
    }

    /** Common conversion hook; native coercion/adapters remain the mapper's responsibility. */
    public static <T> T convert(JsonValue node, JsonType<T> type) {
        Objects.requireNonNull(type, "type");
        java.lang.reflect.Type target = type.type();
        if (node.isMissing() || node.isNull()) {
            return null;
        }
        if (!(node instanceof BaseValue view) || view.mapper == null) {
            throw failure(node, JsonException.Kind.CONFIGURATION, JsonFailure.Reason.CONVERSION,
                    "Present JSON conversion requires a mapper-created tree or explicit mapper.convert");
        }
        try {
            T result = view.mapper.convert(node, type);
            if (result == null && target instanceof Class<?> raw && raw.isPrimitive()) {
                throw new JsonException(JsonException.Kind.BINDING, "Provider returned null for a primitive target");
            }
            return result;
        } catch (JsonException error) {
            throw error.at(node.pointer(), node.displayPath());
        }
    }

    public static JsonObject objectView(JsonValue node) {
        if (node.isMissing() || node.isNull()) {
            return new AbsentObject(node);
        }
        if (node instanceof JsonObject object) {
            return object;
        }
        throw shapeFailure(node, "object");
    }

    public static JsonValue member(JsonValue node, String name) {
        if (!node.isMissing() && !node.isNull() && !(node instanceof JsonObject)) {
            throw shapeFailure(node, "object");
        }
        return node.get(name);
    }

    public static JsonValue element(JsonValue node, int index) {
        if (!node.isMissing() && !node.isNull() && !(node instanceof JsonArray)) {
            throw shapeFailure(node, "array");
        }
        return node.get(index);
    }

    public static JsonArray arrayView(JsonValue node) {
        if (node.isMissing() || node.isNull()) {
            return new AbsentArray(node);
        }
        if (node instanceof JsonArray array) {
            return array;
        }
        throw shapeFailure(node, "array");
    }

    public static JsonException shapeFailure(JsonValue node, String shape) {
        return failure(node, JsonException.Kind.BINDING, JsonFailure.Reason.TYPE_MISMATCH,
                "Expected a JSON " + shape);
    }

    /** Error metadata and custom exceptions are allocated only on failure, outside Provider calls. */
    public static JsonValue require(JsonValue node, boolean valid, JsonFailure.Reason reason,
                                    Function<? super JsonFailure, ? extends RuntimeException> errors) {
        if (valid) {
            return node;
        }
        JsonFailure context = new JsonFailure(JsonException.Kind.VALIDATION, reason,
                "JSON requirement failed", node.pointer(), node.displayPath(), null);
        throw errors == null ? new JsonException(context)
                : Objects.requireNonNull(errors.apply(context), "Exception factory returned null");
    }

    private static JsonException failure(JsonValue node, JsonException.Kind kind,
                                         JsonFailure.Reason reason, String message) {
        return new JsonException(new JsonFailure(kind, reason, message,
                node.pointer(), node.displayPath(), null));
    }

    private static JsonValue wrap(Object value, JsonMapper mapper, JsonPath path) {
        return wrap(value, mapper, path, false);
    }

    private static JsonValue wrap(Object value, JsonMapper mapper, JsonPath path, boolean borrowed) {
        if (value == null && mapper == null && path == JsonPath.ROOT) {
            return JsonValue.NULL;
        }
        if (value instanceof Map<?, ?> map) {
            return new ObjectValue(map, mapper, path, borrowed);
        }
        if (value instanceof List<?> list) {
            return new ArrayValue(list, mapper, path, borrowed);
        }
        return new BaseValue(borrowed ? scalar(value) : value, false, mapper, path);
    }

    private static Object copy(Object value) {
        if (value instanceof JsonValue node) {
            return copy(bindingValue(node));
        }
        if (value instanceof Map<?, ?> map) {
            return copyObject(map);
        }
        if (value instanceof Collection<?> collection) {
            return copyCollection(collection);
        }
        if (value != null && value.getClass().isArray()) {
            return copyArray(value);
        }
        return scalar(value);
    }

    private static Object scalar(Object value) {
        if (value instanceof Number) {
            if (!IMMUTABLE_NUMBERS.contains(value.getClass())) {
                throw new IllegalArgumentException("Use JsonMapper.toTree for custom or mutable Numbers");
            }
            return value;
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        throw new IllegalArgumentException("Use JsonMapper.toTree for Java objects");
    }

    private static Map<String, Object> copyObject(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object keys must be Strings");
            }
            result.put(name, copy(entry.getValue()));
        }
        return result;
    }

    private static List<Object> copyCollection(Collection<?> source) {
        List<Object> result = new ArrayList<>(source.size());
        for (Object child : source) {
            result.add(copy(child));
        }
        return result;
    }

    private static List<Object> copyArray(Object source) {
        int length = Array.getLength(source);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(copy(Array.get(source, i)));
        }
        return result;
    }

    /** Shared storage, mapper and access location for scalar, container and absent views. */
    private static class BaseValue implements JsonValue {
        private final Object value;
        private final boolean missing;
        final JsonMapper mapper;
        final JsonPath path;
        final boolean borrowed;

        BaseValue(Object value, boolean missing, JsonMapper mapper, JsonPath path) {
            this(value, missing, mapper, path, false);
        }

        BaseValue(Object value, boolean missing, JsonMapper mapper, JsonPath path, boolean borrowed) {
            this.value = value;
            this.missing = missing;
            this.mapper = mapper;
            this.path = path;
            this.borrowed = borrowed;
        }

        BaseValue(JsonValue absent) {
            this(null, absent.isMissing(), absent instanceof BaseValue view ? view.mapper : null,
                    absent instanceof BaseValue view ? view.path
                            : JsonPath.location(absent.pointer(), absent.displayPath()));
        }

        void checkPresent() {
            if (missing) {
                throw new IllegalStateException("Missing JSON is not a value");
            }
        }

        void checkWritable() {
            if (borrowed) {
                throw new UnsupportedOperationException("Borrowed JSON views are read-only; use deepCopy to edit");
            }
        }

        JsonException absentWrite() {
            return failure(this, JsonException.Kind.BINDING,
                    missing ? JsonFailure.Reason.MISSING : JsonFailure.Reason.NULL,
                    "Cannot modify an absent JSON container");
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
        public JsonPointer pointer() {
            return path.pointer();
        }

        @Override
        public String displayPath() {
            return path.display();
        }

        @Override
        public JsonValue get(String name) {
            return new BaseValue(null, true, mapper, path.field(name));
        }

        @Override
        public JsonValue get(int index) {
            return new BaseValue(null, true, mapper, path.index(index));
        }

        @Override
        public JsonValue at(JsonPointer pointer) {
            List<String> segments = pointer.segments();
            if (segments.isEmpty()) {
                return this;
            }
            if (segments.size() == 1) {
                return JsonValue.super.at(pointer);
            }
            // Traverse storage without intermediate views; diagnostics must not retain ancestor data.
            Object current = value;
            boolean absent = missing;
            boolean array = this instanceof JsonArray;
            boolean[] indices = null;
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                long index = array ? JsonArrayIndex.parse(segment) : -1;
                if (array && index >= 0 && index <= Integer.MAX_VALUE) {
                    if (indices == null) {
                        indices = new boolean[segments.size()];
                    }
                    indices[i] = true;
                    if (current instanceof List<?> list && index < list.size()) {
                        current = list.get((int) index);
                        absent = false;
                    } else {
                        current = null;
                        absent = true;
                    }
                } else if (current instanceof Map<?, ?> map) {
                    current = map.get(segment);
                    absent = current == null && !map.containsKey(segment);
                } else {
                    current = null;
                    absent = true;
                }
                array = current instanceof List<?>;
            }
            JsonPath selected = path.select(segments, indices);
            return absent ? new BaseValue(null, true, mapper, selected) : wrap(current, mapper, selected, borrowed);
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public JsonValue deepCopy() {
            if (missing) {
                return mapper == null ? JsonValue.MISSING : new BaseValue(null, true, mapper, JsonPath.ROOT);
            }
            return wrap(copy(value), mapper, JsonPath.ROOT);
        }
    }

    private static final class ObjectValue extends BaseValue implements JsonObject {
        private final Map<String, Object> values;

        @SuppressWarnings("unchecked")
        ObjectValue(Map<?, ?> values, JsonMapper mapper, JsonPath path, boolean borrowed) {
            super(values, false, mapper, path, borrowed);
            this.values = (Map<String, Object>) values;
        }

        @Override
        public JsonValue get(String name) {
            JsonPath child = path.field(name);
            Object value = values.get(name);
            return value != null || values.containsKey(name) ? wrap(value, mapper, child, borrowed)
                    : new BaseValue(null, true, mapper, child);
        }

        @Override
        public JsonObject put(String name, Object value) {
            checkWritable();
            values.put(Objects.requireNonNull(name, "name"), copy(value));
            return this;
        }

        @Override
        public JsonObject remove(String name) {
            checkWritable();
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

    private static final class ArrayValue extends BaseValue implements JsonArray {
        private final List<Object> values;

        @SuppressWarnings("unchecked")
        ArrayValue(List<?> values, JsonMapper mapper, JsonPath path, boolean borrowed) {
            super(values, false, mapper, path, borrowed);
            this.values = (List<Object>) values;
        }

        @Override
        public JsonValue get(int index) {
            JsonPath child = path.index(index);
            return index >= 0 && index < values.size() ? wrap(values.get(index), mapper, child, borrowed)
                    : new BaseValue(null, true, mapper, child);
        }

        @Override
        public JsonArray add(Object value) {
            checkWritable();
            values.add(copy(value));
            return this;
        }

        @Override
        public JsonArray set(int index, Object value) {
            checkWritable();
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

    /** Absent typed views are read-only and distinct from actual empty containers. */
    private static final class AbsentObject extends BaseValue implements JsonObject {
        AbsentObject(JsonValue node) {
            super(node);
        }

        @Override
        public JsonObject put(String name, Object value) {
            throw absentWrite();
        }

        @Override
        public JsonObject remove(String name) {
            throw absentWrite();
        }

        @Override
        public JsonObject deepCopy() {
            return new AbsentObject(super.deepCopy());
        }
    }

    private static final class AbsentArray extends BaseValue implements JsonArray {
        AbsentArray(JsonValue node) {
            super(node);
        }

        @Override
        public JsonArray add(Object value) {
            throw absentWrite();
        }

        @Override
        public JsonArray set(int index, Object value) {
            throw absentWrite();
        }

        @Override
        public JsonArray deepCopy() {
            return new AbsentArray(super.deepCopy());
        }
    }
}
