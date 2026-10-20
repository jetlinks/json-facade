package org.jetlinks.json;

/** Mutable JSON object with String keys. Inserted containers are copied, including nested nodes.
 * @since 1.0.0
 */
public interface JsonObject extends JsonValue {
    @Override
    JsonObject put(String name, Object value);
    @Override
    JsonObject remove(String name);
    @Override
    JsonObject deepCopy();
}
