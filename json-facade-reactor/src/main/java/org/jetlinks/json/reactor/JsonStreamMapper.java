package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/** Immutable Reactor adapter over one JsonMapper. State is created per subscription.
 * Uses bounded demand-driven composition, no schedulers, blocking I/O or internal subscriptions.
 * @since 1.0.0
 */
public interface JsonStreamMapper {
    /** Bind an already configured mapper; no provider rediscovery or configuration copying. */
    static JsonStreamMapper of(JsonMapper mapper) {
        return new DefaultJsonStreamMapper(java.util.Objects.requireNonNull(mapper, "mapper"));
    }

    /** Read owned UTF-8 chunks. Published byte arrays must remain immutable, even after onNext returns.
     * Modes distinguish whole values, array elements, NDJSON and root sequences.
     * Cancellation closes only parser state and cancels the input publisher.
     */
    <T> Flux<T> readBytes(Publisher<byte[]> input, JsonType<T> type, JsonReadOptions options);

    /** Read the remaining bytes of any ByteBuffer kind, preserving its position/limit/mark.
     * Copies in the input subscriber's onNext before queuing; the source may then reuse its storage.
     * No retain/release ownership is inferred from ByteBuffer.
     */
    <T> Flux<T> readBuffers(Publisher<? extends ByteBuffer> input, JsonType<T> type, JsonReadOptions options);

    /** Encode records compactly with a bounded record buffer and demand-driven chunks.
     * On error/cancel, no successful closing bracket is appended; emitted bytes cannot be rolled back.
     * Output arrays are independently owned, never reused by this library.
     */
    Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options);

    /** Same encoding as writeBytes; returns read-only buffers with independent cursors, no release needed. */
    Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options);
}
