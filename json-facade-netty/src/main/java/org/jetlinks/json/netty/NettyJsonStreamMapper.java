package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.Objects;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/** Immutable Netty buffer adapter over a configured mapper and host allocator.
 * Input views avoid whole-chunk copies; native decoding/binding still allocate. Output uses
 * allocated ByteBuf segments but native generator-to-segment writes still copy encoded bytes.
 * Per-subscription state follows demand, with no scheduler, aggregation or internal subscription.
 * @since 1.0.0
 * @see JsonStreamMapper
 */
public final class NettyJsonStreamMapper {
    private final JsonStreamMapper streams;
    private final NettyBufferAllocator allocator;

    private NettyJsonStreamMapper(JsonMapper mapper, ByteBufAllocator allocator) {
        streams = JsonStreamMapper.of(mapper);
        this.allocator = new NettyBufferAllocator(allocator);
    }

    /** Reuse the configured mapper and channel allocator, including its heap/direct preference.
     * No live buffers are allocated before subscription.
     */
    public static NettyJsonStreamMapper of(JsonMapper mapper, ByteBufAllocator allocator) {
        return new NettyJsonStreamMapper(Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(allocator, "allocator"));
    }

    /** Borrow input and retain one reference during onNext, compatible with Reactor Netty receive().
     * The source releases its original reference; the adapter releases only its retained lease.
     * Bytes/components must stay stable until consumption. Indices are preserved. Async boundaries
     * before delivery require upstream retention; do not call retain() on receive() for this method.
     */
    public <T> Flux<T> read(Publisher<? extends ByteBuf> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input, true), type, options);
    }

    /** Class counterpart of read, borrowing input and retaining one reference during onNext. */
    public <T> Flux<T> read(Publisher<? extends ByteBuf> input, Class<T> type, JsonReadOptions options) {
        return read(input, JsonType.of(type), options);
    }

    /** Accept one transferred reference per delivered buffer, without retaining another.
     * Use for subscriber-owned buffers or already retained receive() input. Upstream owns buffers
     * it has not delivered and must clean its own queued discards. Do not release transferred refs.
     */
    public <T> Flux<T> readOwned(Publisher<? extends ByteBuf> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input, false), type, options);
    }

    /** Class counterpart of readOwned, consuming one transferred input reference. */
    public <T> Flux<T> readOwned(Publisher<? extends ByteBuf> input, Class<T> type, JsonReadOptions options) {
        return readOwned(input, JsonType.of(type), options);
    }

    /** Stream a whole document's immutable events with the borrowed/retained ownership of read(). */
    public Flux<JsonEvent> readEvents(Publisher<? extends ByteBuf> input) {
        return streams.readChunkEvents(chunks(input, true));
    }

    /** Structural input with the transferred-reference ownership of readOwned(). */
    public Flux<JsonEvent> readOwnedEvents(Publisher<? extends ByteBuf> input) {
        return streams.readChunkEvents(chunks(input, false));
    }

    /** Encode compact records into bounded allocator-owned segments. Each emitted buffer transfers
     * one reference downstream: Reactor Netty send() owns accepted buffers; other consumers release
     * them and arrange cleanup for any queues/discards they introduce. The adapter releases only
     * unpublished buffers on error/cancellation. Binding and token budgets remain authoritative.
     */
    public Flux<ByteBuf> write(Publisher<?> values, JsonWriteOptions options) {
        return streams.writeChunks(values, options, allocator);
    }

    /** Stream one structured SINGLE_VALUE document with the output ownership of write(). */
    public Flux<ByteBuf> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return streams.writeEventChunks(events, options, allocator);
    }

    private static Flux<JsonInputChunk> chunks(Publisher<? extends ByteBuf> input, boolean retain) {
        Objects.requireNonNull(input, "input");
        // Prevent fusion from delaying retain until a queue poll, after the source's onNext returned.
        return Flux.from(input).hide().map(buffer -> lease(buffer, retain));
    }

    private static JsonInputChunk lease(ByteBuf buffer, boolean retain) {
        if (retain) {
            buffer.retain();
        }
        return new NettyInputChunk(buffer);
    }
}
