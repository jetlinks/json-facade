package org.jetlinks.json;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Immutable configured JSON binding instance. Safe to share; cursors/sessions are operation-local.
 * Manual operations only: never configures HTTP codecs, framework mappers or global state.
 * @since 1.0.0
 * @see org.jetlinks.json.spi.JsonProvider
 */
public interface JsonMapper {
    /** Immutable portable policies and budgets, also used by stream adapters.
     * Native snapshot adapters retain richer native binding settings; these flags are not a full
     * description of engine-specific inclusion, coercion or per-property rules.
     */
    JsonOptions options();
    /** Serialize one root value. Caller owns and closes the target. */
    void write(Object value, OutputStream output);
    /** Serialize compact JSON for this operation without changing the shared mapper's pretty policy.
     * Caller owns the target; binding modules and other output policies remain effective.
     * @since 1.0.0
     */
    void writeCompact(Object value, OutputStream output);
    /** Serialize one root value to a caller-owned character target. */
    void write(Object value, Writer output);
    /** Read exactly one complete value and reject trailing content. Does not close input. */
    <T> T read(InputStream input, JsonType<T> type);
    /** Character-input counterpart of read(InputStream, JsonType). */
    <T> T read(Reader input, JsonType<T> type);
    /** Bind through JSON data semantics; does not preserve source object identity. */
    <T> T convert(Object value, JsonType<T> type);
    /** Create a cursor at the start of a caller-owned source. */
    JsonReader reader(InputStream input);
    /** Create a token writer over a caller-owned target. */
    JsonWriter writer(OutputStream output);
    /** Compact reusable writer without an implicit root separator. Caller owns framing and target.
     * Create one per operation/subscription; close does not repair incomplete containers.
     * @since 1.0.0
     */
    JsonWriter compactWriter(OutputStream output);
    /** Incremental events of one document. Containers are never collected; scalar/parser limits apply.
     * Input borrowing, cancellation and cleanup follow readSession.
     * @since 1.0.0
     */
    JsonReadSession<JsonEvent> eventSession();
    /** Create an independent, nonblocking UTF-8 parser session; options are fixed for its life. */
    <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions options);

    /** New mutable object; providers may choose their storage implementation. */
    default JsonObject createObject() {
        return JsonValue.object();
    }

    /** Copy nested JSON containers, preserving explicit nulls. POJO conversion belongs to toTree. */
    default JsonObject createObject(java.util.Map<String, ?> values) {
        JsonObject result = createObject();
        for (java.util.Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object keys must be Strings");
            }
            result.put(name, entry.getValue());
        }
        return result;
    }

    /** New mutable array; providers may choose their storage implementation. */
    default JsonArray createArray() {
        return JsonValue.array();
    }

    /** Copy nested JSON containers, preserving explicit nulls. POJO conversion belongs to toTree. */
    default JsonArray createArray(java.util.Collection<?> values) {
        JsonArray result = createArray();
        values.forEach(result::add);
        return result;
    }

    default byte[] writeBytes(Object value) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    default String write(Object value) {
        return new String(writeBytes(value), StandardCharsets.UTF_8);
    }

    default <T> T read(byte[] input, JsonType<T> type) {
        return read(new java.io.ByteArrayInputStream(input), type);
    }

    default <T> T read(String input, JsonType<T> type) {
        return read(new java.io.StringReader(input), type);
    }

    default <T> T read(String input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    default <T> T read(byte[] input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    default JsonValue readTree(String input) {
        return JsonValue.of(read(input, Object.class));
    }

    default JsonValue toTree(Object value) {
        return JsonValue.of(convert(value, JsonType.of(Object.class)));
    }
}
