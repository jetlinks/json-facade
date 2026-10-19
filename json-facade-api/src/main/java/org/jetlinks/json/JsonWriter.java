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
    void flush();
    /** Release generator state without completing open containers or closing the target. */
    @Override
    void close();
}
