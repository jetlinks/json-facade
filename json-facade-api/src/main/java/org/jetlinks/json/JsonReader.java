package org.jetlinks.json;

/** Operation-scoped synchronous token cursor. Not thread-safe.
 * Closing releases parser state, never the caller's source.
 * @since 1.0.0
 */
public interface JsonReader extends AutoCloseable {
    /** Advance one token; null means genuine EOF. Parsing errors throw JsonException. */
    JsonToken next();
    /** Current token, or null before first advance and after binding a whole value. */
    JsonToken current();
    /** Field name or textual scalar representation at the current token. */
    String text();
    /** Bind the entire current value; next() then advances to its successor. */
    <T> T readValue(JsonType<T> type);
    /** Skip the current scalar or entire container without materializing it. */
    void skipValue();
    /** Release internal state, leaving caller-owned input open. */
    @Override
    void close();
}
