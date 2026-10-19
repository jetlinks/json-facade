package org.jetlinks.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Neutral mutable JSON tree backed by JDK values. NULL and MISSING are distinct non-null nodes.
 * Trees are not thread-safe; deepCopy() isolates nested maps/lists.
 * @since 1.0.0
 */
public final class JsonValue {
    public static final JsonValue NULL = new JsonValue(null, false);
    public static final JsonValue MISSING = new JsonValue(null, true);
    private final Object value;
    private final boolean missing;

    private JsonValue(Object value, boolean missing) {
        this.value = value;
        this.missing = missing;
    }

    public static JsonValue of(Object value) {
        if (value instanceof JsonValue node) {
            return node;
        }
        return value == null ? NULL : new JsonValue(value, false);
    }

    public static JsonValue object() {
        return of(new LinkedHashMap<String, Object>());
    }

    public static JsonValue array() {
        return of(new ArrayList<>());
    }

    public boolean isNull() {
        return !missing && value == null;
    }

    public boolean isMissing() {
        return missing;
    }

    public Object toJavaValue() {
        if (missing) {
            throw new IllegalStateException("Missing JSON is not a value");
        }
        return value;
    }

    public JsonValue get(String name) {
        if (value instanceof Map<?, ?> map && map.containsKey(name)) {
            return of(map.get(name));
        }
        return MISSING;
    }

    public JsonValue get(int index) {
        if (value instanceof List<?> list && index >= 0 && index < list.size()) {
            return of(list.get(index));
        }
        return MISSING;
    }

    public JsonValue at(JsonPointer pointer) {
        JsonValue current = this;
        for (String segment : pointer.segments()) {
            if (current.value instanceof List<?>) {
                if (!segment.matches("0|[1-9][0-9]*")) {
                    return MISSING;
                }
                try {
                    current = current.get(Integer.parseInt(segment));
                } catch (NumberFormatException e) {
                    return MISSING;
                }
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }

    public JsonValue put(String name, Object item) {
        Map<String, Object> map = objectValue();
        map.put(Objects.requireNonNull(name, "name"), unwrap(item));
        return this;
    }

    public JsonValue remove(String name) {
        objectValue().remove(name);
        return this;
    }

    public JsonValue add(Object item) {
        arrayValue().add(unwrap(item));
        return this;
    }

    public JsonValue set(int index, Object item) {
        arrayValue().set(index, unwrap(item));
        return this;
    }

    public int size() {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value instanceof List<?> list ? list.size() : 0;
    }

    public JsonValue deepCopy() {
        return missing ? MISSING : of(copy(value));
    }

    private static Object unwrap(Object item) {
        return item instanceof JsonValue node ? node.toJavaValue() : item;
    }

    private static Object copy(Object item) {
        if (item instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put((String) key, copy(child)));
            return result;
        }
        if (item instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(child -> result.add(copy(child)));
            return result;
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectValue() {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Expected a JSON object");
        }
        // All maps created by the tree and JSON engines have String keys.
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> arrayValue() {
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException("Expected a JSON array");
        }
        return (List<Object>) value;
    }
}
