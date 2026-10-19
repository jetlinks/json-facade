package org.jetlinks.json;

import java.nio.ByteBuffer;

/** Demand-driven UTF-8 parsing SPI; one session per read/subscription, never shared concurrently.
 * Provider implementations own parser state only, with no threads or external I/O.
 * @param <T> bound record type
 * @since 1.0.0
 * @see JsonMapper#readSession(JsonType, JsonReadOptions)
 */
public interface JsonReadSession<T> extends AutoCloseable {
    /** True only when the previous input is consumed and another segment may be fed. */
    boolean needsInput();
    /** Borrow remaining bytes until needsInput() becomes true or close().
     * The caller must preserve the bytes; position/limit/mark must remain unchanged.
     * Heap, direct, read-only and sliced buffers are required. Call only when needsInput().
     */
    void feed(ByteBuffer utf8);
    /** Produce at most one record, request input, or report validated EOF.
     * No unbounded result prefetch. A VALUE result may contain Java null.
     * Errors throw JsonException and are never silently skipped.
     */
    Result<T> read();
    /** Signal genuine EOF once; incomplete tokens/documents must fail in read(). */
    void endInput();
    /** Idempotently release parser and current record state; never release caller memory. */
    @Override
    void close();

    /** Explicit tagged result, separating JSON null from NEED_INPUT and END. */
    record Result<T>(State state, T value) {
        public static <T> Result<T> value(T value) {
            return new Result<>(State.VALUE, value);
        }

        public static <T> Result<T> needInput() {
            return new Result<>(State.NEED_INPUT, null);
        }

        public static <T> Result<T> end() {
            return new Result<>(State.END, null);
        }
    }

    /** Session read result state. */
    enum State { VALUE, NEED_INPUT, END }
}
