package org.jetlinks.json;

import org.jetlinks.json.internal.JsonValues;

/** Engine-neutral JSON value. Containers are mutable and operation-local; NULL differs from MISSING.
 * Java-value export and deepCopy isolate nested containers. Use a configured mapper for POJO mapping.
 * @since 1.0.0
 */
public interface JsonValue {
    JsonValue NULL = JsonValues.nullValue();
    JsonValue MISSING = JsonValues.missingValue();

    /** Copy JSON-compatible Java containers, or retain an existing node's identity. */
    static JsonValue of(Object value) {
        return JsonValues.of(value);
    }

    /** Configuration-free structural shortcut; application factories live on JsonMapper. */
    static JsonObject object() {
        return JsonValues.object();
    }

    /** Configuration-free structural shortcut; application factories live on JsonMapper. */
    static JsonArray array() {
        return JsonValues.array();
    }

    boolean isNull();
    boolean isMissing();
    /** Export an independent Map/List graph; MISSING has no Java value. */
    Object toJavaValue();
    JsonValue get(String name);
    JsonValue get(int index);
    int size();
    JsonValue deepCopy();

    default JsonObject asObject() {
        if (this instanceof JsonObject object) {
            return object;
        }
        throw new IllegalStateException("Expected a JSON object");
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray array) {
            return array;
        }
        throw new IllegalStateException("Expected a JSON array");
    }

    default JsonValue at(JsonPointer pointer) {
        JsonValue current = this;
        for (String segment : pointer.segments()) {
            if (current instanceof JsonArray) {
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

    default JsonObject put(String name, Object value) {
        return asObject().put(name, value);
    }

    default JsonObject remove(String name) {
        return asObject().remove(name);
    }

    default JsonArray add(Object value) {
        return asArray().add(value);
    }

    default JsonArray set(int index, Object value) {
        return asArray().set(index, value);
    }
}
