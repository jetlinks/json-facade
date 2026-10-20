package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
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

    /** Stream immutable tokens of exactly one document, retaining all structural fields.
     * Containers are never accumulated. Complete tokens remain subject to mapper limits.
     */
    Flux<JsonEvent> readEvents(Publisher<byte[]> input);

    /** Event input with the same immediate-copy ownership as readBuffers. */
    Flux<JsonEvent> readBufferEvents(Publisher<? extends ByteBuffer> input);

    /** Read transferred transport leases without copying their bytes. Each delivered lease is closed
     * after consumption, or after active parser cleanup on error/cancellation. Upstream owns undelivered
     * leases and its queues; bytes must stay stable until close. No source retain/release is inferred.
     */
    <T> Flux<T> readChunks(Publisher<? extends JsonInputChunk> input, JsonType<T> type, JsonReadOptions options);

    /** Structural input with the same transferred-lease ownership as readChunks. */
    Flux<JsonEvent> readChunkEvents(Publisher<? extends JsonInputChunk> input);

    /** Encode records compactly with bounded owned segments and demand-driven chunks.
     * On error/cancel, no successful closing bracket is appended; emitted bytes cannot be rolled back.
     * Output arrays are independently owned, never reused by this library.
     */
    Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options);

    /** Same encoding as writeBytes; returns read-only buffers with independent cursors, no release needed. */
    Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options);

    /** Write one arbitrarily large structured document incrementally, using SINGLE_VALUE format.
     * Each token/bound value is byte bounded; downstream demand gates subsequent events.
     * Input events and bound Java values must remain stable until consumed.
     */
    Flux<byte[]> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options);

    /** Structural output with independent read-only ByteBuffer cursors. */
    Flux<ByteBuffer> writeEventBuffers(Publisher<? extends JsonEvent> events, JsonWriteOptions options);

    /** Encode into transport-allocated segments. Publication transfers buffer ownership to downstream,
     * which releases consumed buffers and any queues/discards it introduces. Unpublished buffers are
     * released by this adapter. The allocator is shared; buffers and native state are per subscription.
     */
    <B> Flux<B> writeChunks(Publisher<?> values, JsonWriteOptions options, JsonBufferAllocator<B> allocator);

    /** Structural output with the same transfer/cleanup contract as writeChunks. */
    <B> Flux<B> writeEventChunks(Publisher<? extends JsonEvent> events, JsonWriteOptions options,
                                JsonBufferAllocator<B> allocator);
}
