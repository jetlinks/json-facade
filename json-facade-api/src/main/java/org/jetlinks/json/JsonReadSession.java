package org.jetlinks.json;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;

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
    /** Read with cooperative cancellation, checked between tokens when supported by the provider.
     * A cancelled read may return END without validating the remainder; the caller must still close.
     * The predicate must be thread-safe and must not block or mutate the parser.
     * @param cancelled indicates that the caller no longer needs a result
     * @return the next result, or END after cancellation
     * @since 1.0.0
     */
    default Result<T> read(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() ? Result.end() : read();
    }
    /** Deliver one result synchronously without requiring a Result wrapper.
     * The receiver runs on the reading thread exactly once after a successful read, including
     * NEED_INPUT and END. VALUE may carry Java null; other states carry null.
     * The receiver must not block or reenter the session. Its exception propagates unchanged;
     * the caller must close the failed operation. Providers retain all parser/resource ownership.
     * The default bridges the pull API; providers can share one execution core with both forms.
     * @param receiver maps the state/value to a caller-selected result, never null
     * @param cancelled cooperative cancellation predicate, never null
     * @param <R> receiver result type, which may itself be null
     * @return the receiver's result
     * @since 1.0.0
     * @see Receiver
     */
    default <R> R read(Receiver<? super T, R> receiver, BooleanSupplier cancelled) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(cancelled, "cancelled");
        Result<T> result = read(cancelled);
        return receiver.receive(result.state(), result.value());
    }
    /** Signal genuine EOF once; incomplete tokens/documents must fail in read(). */
    void endInput();
    /** Idempotently release parser and current record state; never release caller memory. */
    @Override
    void close();

    /** Operation-local synchronous result delivery, independent of any reactive library.
     * A receiver may be reused by serial reads and may retain a complete delivered value.
     * It must not reenter the session or deliver downstream signals while the caller still
     * owns an active parser operation. Control states and VALUE(null) remain distinct.
     * @param <T> accepted value type
     * @param <R> caller-selected result type
     * @since 1.0.0
     * @see #read(Receiver, BooleanSupplier)
     */
    @FunctionalInterface
    interface Receiver<T, R> {
        /** Map one read outcome. VALUE may contain null; control states contain null.
         * @param state non-null read state
         * @param value complete value for VALUE, otherwise null
         * @return caller-selected result, possibly null
         */
        R receive(State state, T value);
    }

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
