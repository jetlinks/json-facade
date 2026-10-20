package org.jetlinks.json.spring;

import java.util.Objects;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import reactor.core.publisher.Flux;

/** Immutable adapter for explicitly composed Spring DataBuffer streams.
 * Each subscription owns its delivered input buffers and unpublished output. Component views
 * avoid whole-input-chunk copies; native decoding and generator-to-output copying still remain.
 * Parsing, framing, budgets and backpressure use the configured Provider and Reactor adapter.
 * No HTTP codecs, schedulers, subscriptions or global registrations are installed.
 * @since 1.0.0
 * @see JsonStreamMapper
 */
public final class DataBufferJsonStreamMapper {
    private final JsonStreamMapper streams;
    private final DataBufferAllocator allocator;

    private DataBufferJsonStreamMapper(JsonMapper mapper, DataBufferFactory factory) {
        streams = JsonStreamMapper.of(mapper);
        allocator = new DataBufferAllocator(factory);
    }

    /** Reuse the configured mapper and a thread-safe host factory, such as response.bufferFactory().
     * Heap/direct and pooling preferences belong to the factory. Allocates no buffers until demand.
     */
    public static DataBufferJsonStreamMapper of(JsonMapper mapper, DataBufferFactory factory) {
        return new DataBufferJsonStreamMapper(Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(factory, "factory"));
    }

    /** Consume one transferred ownership per delivered buffer, as with a Spring request body.
     * Does not retain an additional reference. The adapter releases delivered buffers after parsing
     * or active callback cleanup on error/cancel; upstream owns undelivered buffers and its queues.
     * Bytes must remain stable until consumed. Input positions are preserved. A caller sharing
     * pooled buffers retains/releases its own additional reference before transferring this one.
     */
    public <T> Flux<T> read(Publisher<? extends DataBuffer> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input), type, options);
    }

    /** Stream a document's immutable structural events with the input ownership of read(). */
    public Flux<JsonEvent> readEvents(Publisher<? extends DataBuffer> input) {
        return streams.readChunkEvents(chunks(input));
    }

    /** Encode compact records into bounded factory-allocated segments. Publication transfers each
     * buffer downstream, for example to ServerHttpResponse.writeWith. Other consumers release
     * consumed buffers and any queues/discards they introduce. This adapter releases unpublished
     * buffers on failure/cancellation. Never releases application input values as output discards.
     */
    public Flux<DataBuffer> write(Publisher<?> values, JsonWriteOptions options) {
        return streams.writeChunks(values, options, allocator);
    }

    /** Stream one structured SINGLE_VALUE document with the output ownership of write(). */
    public Flux<DataBuffer> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return streams.writeEventChunks(events, options, allocator);
    }

    private static Flux<JsonInputChunk> chunks(Publisher<? extends DataBuffer> input) {
        Objects.requireNonNull(input, "input");
        // Deliver ownership during onNext; no delayed fusion poll may outlive source delivery.
        return Flux.from(input).hide().map(DataBufferInputChunk::new);
    }
}
