package org.jetlinks.json.internal;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
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
import org.jetlinks.json.JsonOptions;
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

    /**
     * 内部透明节点代理，供同步绑定复用已有存储，避免遍历时反复创建容器视图。
     * 代理须保留所属映射器和所有权；返回值不得是自身，调用方不得保留底层绑定图。
     */
    public interface DelegatingValue extends JsonValue {
        JsonValue delegateValue();
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

    /** 内部适配入口：共享调用方明确提供的可写存储；不复制、不改变其修改语义。 */
    public static JsonObject mutableObject(Map<String, Object> values, JsonMapper mapper) {
        return new ObjectValue(Objects.requireNonNull(values, "values"), mapper, JsonPath.ROOT, false);
    }

    /** mutableObject 的数组入口，供独立迁移模块复用标准集合实现。 */
    public static JsonArray mutableArray(List<Object> values, JsonMapper mapper) {
        return new ArrayValue(Objects.requireNonNull(values, "values"), mapper, JsonPath.ROOT, false);
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
        return value instanceof JsonValue node ? node : wrap(copy(value, null, 0), null, JsonPath.ROOT);
    }

    /** Copy a binding result and attach its configured mapper, including for nested views. */
    public static JsonValue of(Object value, JsonMapper mapper) {
        return wrap(copy(value, mapper, 0), Objects.requireNonNull(mapper, "mapper"), JsonPath.ROOT);
    }

    /** Borrow only for immediate synchronous binding. Providers must not expose or retain this graph. */
    public static Object bindingValue(Object value) {
        if (value instanceof BaseValue node) {
            node.checkPresent();
            return node.value;
        }
        if (value instanceof DelegatingValue node) {
            return bindingValue(node.delegateValue());
        }
        // 自定义集合节点通过普通 JDK 视图交给原生 Map/List 绑定，避免整树快照与重复序列化。
        if (value instanceof JsonObject object) {
            return Collections.unmodifiableMap(object);
        }
        if (value instanceof JsonArray array) {
            return Collections.unmodifiableList(array);
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
            return null;
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
        return node.node(name);
    }

    public static JsonValue element(JsonValue node, int index) {
        if (!node.isMissing() && !node.isNull() && !(node instanceof JsonArray)) {
            throw shapeFailure(node, "array");
        }
        return node.node(index);
    }

    public static JsonArray arrayView(JsonValue node) {
        if (node.isMissing() || node.isNull()) {
            return null;
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
        Object scalarValue = value instanceof JsonValue node ? bindingValue(node) : value;
        return new BaseValue(borrowed ? scalar(scalarValue) : scalarValue, false, mapper, path);
    }

    private static Object copy(Object value, JsonMapper mapper, int depth) {
        return copy(value, depth, mapper == null ? JsonOptions.DEFAULT.maxDepth() : mapper.options().maxDepth());
    }

    private static Object copy(Object value, int depth, int maxDepth) {
        if (value instanceof JsonValue node) {
            return copy(bindingValue(node), depth, maxDepth);
        }
        if (value instanceof Map<?, ?> map) {
            return copyObject(map, nestedDepth(depth, maxDepth), maxDepth);
        }
        if (value instanceof Collection<?> collection) {
            return copyCollection(collection, nestedDepth(depth, maxDepth), maxDepth);
        }
        if (value != null && value.getClass().isArray()) {
            return copyArray(value, nestedDepth(depth, maxDepth), maxDepth);
        }
        return scalar(value);
    }

    private static int nestedDepth(int depth, int maxDepth) {
        // 结构复制不经过 Provider；复用深度预算同时限制深树和环，不建立另一套对象引用表。
        if (depth >= maxDepth) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON tree copy exceeds maxDepth: " + maxDepth);
        }
        return depth + 1;
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

    private static Map<String, Object> copyObject(Map<?, ?> source, int depth, int maxDepth) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object keys must be Strings");
            }
            result.put(name, copy(entry.getValue(), depth, maxDepth));
        }
        return result;
    }

    private static List<Object> copyCollection(Collection<?> source, int depth, int maxDepth) {
        List<Object> result = new ArrayList<>(source.size());
        for (Object child : source) {
            result.add(copy(child, depth, maxDepth));
        }
        return result;
    }

    private static List<Object> copyArray(Object source, int depth, int maxDepth) {
        int length = Array.getLength(source);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(copy(Array.get(source, i), depth, maxDepth));
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
            return copy(value, mapper, 0);
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
        public JsonValue node(String name) {
            return new BaseValue(null, true, mapper, path.field(name));
        }

        @Override
        public JsonValue node(int index) {
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
            return wrap(copy(value, mapper, 0), mapper, JsonPath.ROOT);
        }
    }

    /** Map 契约直接委托 JDK；节点访问单独携带路径，不改变普通 get 的值类型。 */
    private static final class ObjectValue extends BaseValue implements JsonObject {
        private final Map<String, Object> raw;
        private final Map<String, Object> values;

        @SuppressWarnings("unchecked")
        ObjectValue(Map<?, ?> source, JsonMapper mapper, JsonPath path, boolean borrowed) {
            super(source, false, mapper, path, borrowed);
            raw = (Map<String, Object>) source;
            values = borrowed ? Collections.unmodifiableMap(readOnlyObject(raw, mapper, path)) : raw;
        }

        @Override
        public JsonValue node(String name) {
            JsonPath child = path.field(name);
            Object value = raw.get(name);
            return value != null || raw.containsKey(name) ? wrap(value, mapper, child, borrowed)
                    : new BaseValue(null, true, mapper, child);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return values.containsValue(value);
        }

        @Override
        public Object get(Object key) {
            return values.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            return values.put(Objects.requireNonNull(key, "key"), value);
        }

        @Override
        public Object remove(Object key) {
            return values.remove(key);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public Set<String> keySet() {
            return values.keySet();
        }

        @Override
        public Collection<Object> values() {
            return values.values();
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return values.entrySet();
        }

        @Override
        public Object getOrDefault(Object key, Object fallback) {
            return values.getOrDefault(key, fallback);
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
            return values.putIfAbsent(Objects.requireNonNull(key, "key"), value);
        }

        @Override
        public boolean remove(Object key, Object value) {
            return values.remove(key, value);
        }

        @Override
        public Object replace(String key, Object value) {
            return values.replace(key, value);
        }

        @Override
        public boolean replace(String key, Object oldValue, Object newValue) {
            return values.replace(key, oldValue, newValue);
        }

        @Override
        public void replaceAll(BiFunction<? super String, ? super Object, ?> function) {
            values.replaceAll(function);
        }

        @Override
        public Object computeIfAbsent(String key, Function<? super String, ?> function) {
            return values.computeIfAbsent(Objects.requireNonNull(key, "key"), function);
        }

        @Override
        public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> function) {
            return values.computeIfPresent(Objects.requireNonNull(key, "key"), function);
        }

        @Override
        public Object compute(String key, BiFunction<? super String, ? super Object, ?> function) {
            return values.compute(Objects.requireNonNull(key, "key"), function);
        }

        @Override
        public Object merge(String key, Object value, BiFunction<? super Object, ? super Object, ?> function) {
            return values.merge(Objects.requireNonNull(key, "key"), value, function);
        }

        @Override
        public boolean equals(Object other) {
            return values.equals(other);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public JsonObject deepCopy() {
            return (JsonObject) super.deepCopy();
        }

        @Override
        public void putAll(Map<? extends String, ?> source) {
            checkWritable();
            source.forEach(this::put);
        }
    }

    private static final class ArrayValue extends BaseValue implements JsonArray {
        private final List<Object> raw;
        private final List<Object> values;

        @SuppressWarnings("unchecked")
        ArrayValue(List<?> source, JsonMapper mapper, JsonPath path, boolean borrowed) {
            super(source, false, mapper, path, borrowed);
            raw = (List<Object>) source;
            values = borrowed ? Collections.unmodifiableList(new AbstractList<>() {
                @Override
                public Object get(int index) {
                    return readOnlyChild(raw.get(index), mapper, path.index(index));
                }

                @Override
                public int size() {
                    return raw.size();
                }
            }) : raw;
        }

        @Override
        public JsonValue node(int index) {
            JsonPath child = path.index(index);
            return index >= 0 && index < raw.size() ? wrap(raw.get(index), mapper, child, borrowed)
                    : new BaseValue(null, true, mapper, child);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean contains(Object value) {
            return values.contains(value);
        }

        @Override
        public Iterator<Object> iterator() {
            return values.iterator();
        }

        @Override
        public Object[] toArray() {
            return values.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array) {
            return values.toArray(array);
        }

        @Override
        public Object get(int index) {
            return values.get(index);
        }

        @Override
        public Object set(int index, Object value) {
            return values.set(index, value);
        }

        @Override
        public boolean add(Object value) {
            return values.add(value);
        }

        @Override
        public void add(int index, Object value) {
            values.add(index, value);
        }

        @Override
        public Object remove(int index) {
            return values.remove(index);
        }

        @Override
        public boolean remove(Object value) {
            return values.remove(value);
        }

        @Override
        public boolean containsAll(Collection<?> source) {
            return values.containsAll(source);
        }

        @Override
        public boolean addAll(Collection<?> source) {
            return values.addAll(source);
        }

        @Override
        public boolean addAll(int index, Collection<?> source) {
            return values.addAll(index, source);
        }

        @Override
        public boolean removeAll(Collection<?> source) {
            return values.removeAll(source);
        }

        @Override
        public boolean retainAll(Collection<?> source) {
            return values.retainAll(source);
        }

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public int indexOf(Object value) {
            return values.indexOf(value);
        }

        @Override
        public int lastIndexOf(Object value) {
            return values.lastIndexOf(value);
        }

        @Override
        public ListIterator<Object> listIterator() {
            return values.listIterator();
        }

        @Override
        public ListIterator<Object> listIterator(int index) {
            return values.listIterator(index);
        }

        @Override
        public List<Object> subList(int start, int end) {
            return values.subList(start, end);
        }

        @Override
        public boolean removeIf(Predicate<? super Object> predicate) {
            return values.removeIf(predicate);
        }

        @Override
        public void replaceAll(UnaryOperator<Object> function) {
            values.replaceAll(function);
        }

        @Override
        public void sort(Comparator<? super Object> comparator) {
            values.sort(comparator);
        }

        @Override
        public boolean equals(Object other) {
            return values.equals(other);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }

        @Override
        public JsonArray deepCopy() {
            return (JsonArray) super.deepCopy();
        }

    }

    /** 外层只读包装不能保护嵌套值；所有读取入口经过同一按需只读视图。 */
    private static Object readOnlyChild(Object value, JsonMapper mapper, JsonPath path) {
        return value instanceof Map<?, ?> || value instanceof List<?> ? wrap(value, mapper, path, true) : scalar(value);
    }

    private static Map<String, Object> readOnlyObject(Map<String, Object> raw, JsonMapper mapper, JsonPath path) {
        return new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                return key instanceof String name ? readOnlyChild(raw.get(name), mapper, path.field(name)) : null;
            }

            @Override
            public boolean containsKey(Object key) {
                return raw.containsKey(key);
            }

            @Override
            public int size() {
                return raw.size();
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public int size() {
                        return raw.size();
                    }

                    @Override
                    public Iterator<Entry<String, Object>> iterator() {
                        Iterator<Entry<String, Object>> source = raw.entrySet().iterator();
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                return source.hasNext();
                            }

                            @Override
                            public Entry<String, Object> next() {
                                Entry<String, Object> entry = source.next();
                                String key = entry.getKey();
                                return new SimpleImmutableEntry<>(key,
                                        readOnlyChild(entry.getValue(), mapper, path.field(key)));
                            }
                        };
                    }
                };
            }
        };
    }
}
