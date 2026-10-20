package org.jetlinks.json;

/** Operation-scoped synchronous JSON writer. Not thread-safe; owns only generator state.
 * close() does not close the target or repair an unfinished document.
 * @since 1.0.0
 */
public interface JsonWriter extends AutoCloseable {
    void beginObject();
    void endObject();
    void beginArray();
    void endArray();
    void name(String name);
    /** Write one value through this mapper's binding configuration, including Java null. */
    void value(Object value);
    /** Write a validated JSON number without a double round trip. No arbitrary raw JSON is accepted. */
    void number(String value);
    /** Write a non-null JSON string token directly, bypassing Java binding adapters. */
    void string(String value);
    /** Write a JSON boolean token directly, bypassing Java binding adapters. */
    void bool(boolean value);
    /** Write a JSON null token directly. */
    void nullValue();
    void flush();
    /** Release generator state without completing open containers or closing the target. */
    @Override
    void close();
}
