package org.jetlinks.json.spi;

import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonWriter;

/** Binding extension for one exact Java class. Instances must be thread-safe.
 * Called on the mapper's thread; no hidden scheduling, retry or I/O.
 * Nulls follow the mapper policy instead of invoking the adapter.
 * @param <T> supported class
 * @since 1.0.0
 * @see JsonModuleContext
 */
public interface JsonAdapter<T> {
    /** Write exactly one JSON value; nested values may use the same configured writer.
     * Do not recursively delegate the same value back to this adapter.
     * @param value non-null value of the registered class
     * @param writer borrowed current writer; do not close or retain it
     * @throws org.jetlinks.json.JsonException when the value cannot be encoded; no retries are performed
     * @since 1.0.0
     */
    void write(T value, JsonWriter writer);
    /** Consume exactly one current value and return the bound object, possibly null.
     * Binding errors propagate to the caller; the reader is borrowed and must not be closed.
     * @param reader borrowed reader positioned at the value's first token; do not retain it
     * @return the decoded value, possibly null; reactive null policy is applied by the stream adapter
     * @throws org.jetlinks.json.JsonException when binding fails; no retries are performed
     * @since 1.0.0
     */
    T read(JsonReader reader);
}
