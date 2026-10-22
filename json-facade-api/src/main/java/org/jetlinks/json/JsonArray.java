package org.jetlinks.json;

/** JSON array. Factory-created arrays copy inserted containers; explicit null remains a JSON value.
 * Borrowed views from JsonMapper.wrap reject writes; deepCopy creates independent mutable storage.
 * Views add no synchronization and keep their access location, without retaining ancestor data.
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
