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

    /** Convert using the owning mapper's Provider. Missing/null yields null; present failures propagate.
     * Configuration-free structural nodes require explicit mapper.convert for present values.
     * Implementations with custom storage must override this binding hook.
     */
    default <T> T as(JsonType<T> type) {
        return JsonValues.convert(this, type);
    }

    default <T> T as(Class<T> type) {
        return as(JsonType.of(type));
    }

    /** Absolute access location within this view's root; detached deep copies start at the root. */
    default JsonPointer pointer() {
        return JsonPointer.of("");
    }

    default String displayPath() {
        return "$";
    }

    /** Non-null typed views. Absent/null views remain readable; writes fail and never create data. */
    default JsonObject getObject(String name) {
        return JsonValues.objectView(JsonValues.member(this, name));
    }

    default JsonObject getObject(int index) {
        return JsonValues.objectView(JsonValues.element(this, index));
    }

    default JsonArray getArray(String name) {
        return JsonValues.arrayView(JsonValues.member(this, name));
    }

    default JsonArray getArray(int index) {
        return JsonValues.arrayView(JsonValues.element(this, index));
    }

    /** Require an existing location, allowing explicit JSON null. */
    default JsonValue requirePresent() {
        return JsonValues.require(this, !isMissing(), org.jetlinks.json.JsonFailure.Reason.MISSING, null);
    }

    default JsonValue requirePresent(java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, !isMissing(), JsonFailure.Reason.MISSING, errors);
    }

    /** Require an existing non-null JSON value. Empty strings/containers are allowed. */
    default JsonValue requireNonNull() {
        return JsonValues.require(this, !isMissing() && !isNull(),
                isMissing() ? JsonFailure.Reason.MISSING : JsonFailure.Reason.NULL, null);
    }

    default JsonValue requireNonNull(java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, !isMissing() && !isNull(),
                isMissing() ? JsonFailure.Reason.MISSING : JsonFailure.Reason.NULL, errors);
    }

    /** Evaluate once. Predicate failures and custom exception instances propagate unchanged. */
    default JsonValue require(java.util.function.Predicate<? super JsonValue> predicate) {
        return JsonValues.require(this, predicate.test(this), JsonFailure.Reason.ASSERTION, null);
    }

    default JsonValue require(java.util.function.Predicate<? super JsonValue> predicate,
                             java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, predicate.test(this), JsonFailure.Reason.ASSERTION, errors);
    }

    /** Missing/null returns 0; present values use Provider conversion, including custom coercion. */
    default int asInt() {
        return asInt(0);
    }

    default int asInt(int defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.INT);
    }

    /** Missing/null returns 0L; present values use Provider conversion, including custom coercion. */
    default long asLong() {
        return asLong(0L);
    }

    default long asLong(long defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.LONG);
    }

    /** Missing/null returns 0D; present values use Provider conversion, including custom coercion. */
    default double asDouble() {
        return asDouble(0D);
    }

    default double asDouble(double defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.DOUBLE);
    }

    /** Missing/null returns 0F; present values use Provider conversion, including custom coercion. */
    default float asFloat() {
        return asFloat(0F);
    }

    default float asFloat(float defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.FLOAT);
    }

    /** Missing/null returns (short) 0; present values use Provider conversion, including custom coercion. */
    default short asShort() {
        return asShort((short) 0);
    }

    default short asShort(short defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.SHORT);
    }

    /** Missing/null returns (byte) 0; present values use Provider conversion, including custom coercion. */
    default byte asByte() {
        return asByte((byte) 0);
    }

    default byte asByte(byte defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BYTE);
    }

    /** Missing/null returns false; present values use Provider conversion, including custom coercion. */
    default boolean asBoolean() {
        return asBoolean(false);
    }

    default boolean asBoolean(boolean defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BOOLEAN);
    }

    /** Missing/null returns null; present values use Provider conversion, including custom coercion. */
    default String asString() {
        return asString(null);
    }

    default String asString(String defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.STRING);
    }

    /** Missing/null returns null; present values use Provider conversion, including custom coercion. */
    default Integer asInteger() {
        return asInteger(null);
    }

    default Integer asInteger(Integer defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.INTEGER);
    }

    /** Missing/null returns null; present values use Provider conversion, including custom coercion. */
    default java.math.BigDecimal asBigDecimal() {
        return asBigDecimal(null);
    }

    default java.math.BigDecimal asBigDecimal(java.math.BigDecimal defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BIG_DECIMAL);
    }

    /** Missing/null returns null; present values use Provider conversion, including custom coercion. */
    default java.math.BigInteger asBigInteger() {
        return asBigInteger(null);
    }

    default java.math.BigInteger asBigInteger(java.math.BigInteger defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BIG_INTEGER);
    }

    default JsonObject asObject() {
        if (!isMissing() && !isNull() && this instanceof JsonObject object) {
            return object;
        }
        throw JsonValues.shapeFailure(this, "object");
    }

    default JsonArray asArray() {
        if (!isMissing() && !isNull() && this instanceof JsonArray array) {
            return array;
        }
        throw JsonValues.shapeFailure(this, "array");
    }

    default JsonValue at(JsonPointer pointer) {
        JsonValue current = this;
        for (String segment : pointer.segments()) {
            if (current instanceof JsonArray) {
                try {
                    if (!canonicalIndex(segment)) {
                        current = current.get(segment);
                    } else {
                        current = current.get(Integer.parseInt(segment));
                    }
                } catch (NumberFormatException e) {
                    current = current.get(segment);
                }
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }

    private static boolean canonicalIndex(String segment) {
        if (segment.isEmpty() || (segment.length() > 1 && segment.charAt(0) == '0')) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) < '0' || segment.charAt(i) > '9') {
                return false;
            }
        }
        return true;
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
