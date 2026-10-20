package org.jetlinks.json;

/** Mutable JSON array. Inserted containers are copied; explicit null remains a JSON value.
 * @since 1.0.0
 */
public interface JsonArray extends JsonValue {
    @Override
    JsonArray add(Object value);
    @Override
    JsonArray set(int index, Object value);
    @Override
    JsonArray deepCopy();
}
