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
    /** Class counterpart of read(InputStream, JsonType); caller retains input ownership. */
    default <T> T read(InputStream input, Class<T> type) {
        return read(input, JsonType.of(type));
    }
    /** Character-input counterpart of read(InputStream, JsonType). */
    <T> T read(Reader input, JsonType<T> type);
    /** Class counterpart of read(Reader, JsonType); caller retains input ownership. */
    default <T> T read(Reader input, Class<T> type) {
        return read(input, JsonType.of(type));
    }
    /** Bind through JSON data semantics; does not preserve source object identity. */
    <T> T convert(Object value, JsonType<T> type);
    /** Class counterpart of convert(Object, JsonType), with the same configured binding semantics. */
    default <T> T convert(Object value, Class<T> type) {
        return convert(value, JsonType.of(type));
    }
    /** Create a cursor at the start of a caller-owned source. */
    JsonReader reader(InputStream input);
    /** Lazily iterate the direct elements of one root array, using configured Provider binding.
     * Creation validates arguments without reading input. hasNext may block on the source but
     * does not bind the next element; next binds only that element, including Java null.
     * Exhaustion validates EOF and closes parser state; parse/binding failure also closes it.
     * Always close early-stopped iterations. The input remains caller-owned in every case.
     * Native token/depth limits apply; incremental-session maxValueBytes does not apply here.
     * @param input caller-owned source, never null
     * @param type element type, never null
     * @param <T> element type
     * @return operation-local iterator, not thread-safe
     * @since 1.0.0
     * @see CloseableIterator
     */
    default <T> CloseableIterator<T> iterateArray(InputStream input, JsonType<T> type) {
        return new org.jetlinks.json.internal.JsonArrayIterator<>(this, input, type);
    }
    /** Class counterpart of iterateArray(InputStream, JsonType), with the same ownership and limits.
     * @since 1.0.0
     */
    default <T> CloseableIterator<T> iterateArray(InputStream input, Class<T> type) {
        return iterateArray(input, JsonType.of(type));
    }
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
    /** Class counterpart of readSession(JsonType, JsonReadOptions); state remains operation-local. */
    default <T> JsonReadSession<T> readSession(Class<T> type, JsonReadOptions options) {
        return readSession(JsonType.of(type), options);
    }

    /** New mutable object; providers may choose their storage implementation. */
    default JsonObject createObject() {
        return org.jetlinks.json.internal.JsonValues.object(this);
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
        return org.jetlinks.json.internal.JsonValues.array(this);
    }

    /** Copy nested JSON containers, preserving explicit nulls. POJO conversion belongs to toTree. */
    default JsonArray createArray(java.util.Collection<?> values) {
        JsonArray result = createArray();
        values.forEach(result::add);
        return result;
    }

    /** Borrow a Map as a read-only JSON object without traversing or copying its contents.
     * The entire source must be acyclic JSON data: String-keyed Maps, Lists, null, String,
     * Boolean and the exact JDK numeric classes accepted by structural factories.
     * Other Collections, Java arrays, JsonValue nodes and POJOs require createObject/toTree.
     * This is not graph validation; unsupported leaves fail when exposed as views.
     * External changes remain visible. Keep the source stable during access, binding and copying;
     * the view adds no synchronization. Child views retain their original child storage.
     * @param values caller-owned source, never null
     * @return configured borrowed view; container writes, including nested writes, throw
     * UnsupportedOperationException. deepCopy returns an independent mutable tree and
     * toJavaValue exports an independent snapshot, both recursively copying on request.
     * @since 1.0.0
     * @see #wrap(java.util.List)
     */
    default JsonObject wrap(java.util.Map<String, ?> values) {
        return org.jetlinks.json.internal.JsonValues.borrowObject(values, this);
    }

    /** Borrow a List as a read-only JSON array under the same source and ownership rules as wrap(Map).
     * No element is visited at creation. Nested containers are wrapped only when accessed;
     * Provider conversion and serialization still use this mapper's configuration.
     * @param values caller-owned source, never null
     * @return configured borrowed view; use deepCopy for an independent mutable tree
     * @since 1.0.0
     * @see #wrap(java.util.Map)
     */
    default JsonArray wrap(java.util.List<?> values) {
        return org.jetlinks.json.internal.JsonValues.borrowArray(values, this);
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
        // Native extensions may return shared containers; a binding result is not proof of exclusive ownership.
        return org.jetlinks.json.internal.JsonValues.of(read(input, Object.class), this);
    }

    default JsonValue toTree(Object value) {
        return org.jetlinks.json.internal.JsonValues.of(convert(value, JsonType.of(Object.class)), this);
    }
}
