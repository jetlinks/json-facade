package org.jetlinks.json;

/** JSON object with String keys. Factory-created objects copy inserted containers, including nested nodes.
 * Borrowed views from JsonMapper.wrap reject writes; deepCopy creates independent mutable storage.
 * Views add no synchronization and keep their access location, without retaining ancestor data.
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
