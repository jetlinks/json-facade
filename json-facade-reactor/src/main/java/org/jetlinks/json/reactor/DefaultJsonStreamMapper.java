package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/** Shared adapter. Reactor's concatMap with zero prefetch serializes one input chunk at a time. */
final class DefaultJsonStreamMapper implements JsonStreamMapper {
    private final JsonMapper mapper;

    DefaultJsonStreamMapper(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> Flux<T> readBytes(Publisher<byte[]> input, JsonType<T> type, JsonReadOptions options) {
        return read(Flux.from(input).map(ByteBuffer::wrap), type, options);
    }

    @Override
    public <T> Flux<T> readBuffers(Publisher<? extends ByteBuffer> input,
                                  JsonType<T> type, JsonReadOptions options) {
        // Copy before concatMap can retain the chunk. A read-only view alone still aliases source memory.
        return read(Flux.from(input).map(DefaultJsonStreamMapper::copy), type, options);
    }

    private static ByteBuffer copy(ByteBuffer input) {
        ByteBuffer view = input.asReadOnlyBuffer();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return ByteBuffer.wrap(bytes);
    }

    private <T> Flux<T> read(Flux<ByteBuffer> input, JsonType<T> type, JsonReadOptions options) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        return read(input, () -> mapper.readSession(type, options), options);
    }

    private static <T> Flux<T> read(Flux<ByteBuffer> input, Supplier<JsonReadSession<T>> factory,
                                    JsonReadOptions options) {
        return Flux.using(() -> new ReadSessionResource<>(factory.get(), options.nullPolicy()), session ->
                input.concatMap(session::decode, 0).concatWith(Flux.defer(session::finish)),
                ReadSessionResource::close);
    }

    @Override
    public Flux<JsonEvent> readEvents(Publisher<byte[]> input) {
        return read(Flux.from(input).map(ByteBuffer::wrap), mapper::eventSession,
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE));
    }

    @Override
    public Flux<JsonEvent> readBufferEvents(Publisher<? extends ByteBuffer> input) {
        return read(Flux.from(input).map(DefaultJsonStreamMapper::copy), mapper::eventSession,
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE));
    }

    @Override
    public <T> Flux<T> readChunks(Publisher<? extends JsonInputChunk> input, JsonType<T> type, JsonReadOptions options) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(options, "options");
        return readChunks(input, () -> mapper.readSession(type, options), options);
    }

    @Override
    public Flux<JsonEvent> readChunkEvents(Publisher<? extends JsonInputChunk> input) {
        return readChunks(input, mapper::eventSession, JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE));
    }

    private static <T> Flux<T> readChunks(Publisher<? extends JsonInputChunk> input,
                                          Supplier<JsonReadSession<T>> factory, JsonReadOptions options) {
        Objects.requireNonNull(input, "input");
        return Flux.using(() -> new ReadSessionResource<>(factory.get(), options.nullPolicy()), session ->
                Flux.from(input).concatMap(session::decodeChunk, 0).concatWith(Flux.defer(session::finish))
                        .doOnDiscard(JsonInputChunk.class, JsonInputChunk::close), ReadSessionResource::close);
    }

    @Override
    public Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options) {
        return writeChunks(values, options, ByteArrayAllocator.INSTANCE);
    }

    @Override
    public <B> Flux<B> writeChunks(Publisher<?> values, JsonWriteOptions options, JsonBufferAllocator<B> allocator) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(allocator, "allocator");
        return Flux.using(() -> new WriteSessionResource<>(mapper, options, false, allocator), session ->
                Flux.concat(Flux.defer(session::start),
                        Flux.from(values).concatMap(session::encode, 0),
                        Flux.defer(session::finish)).doOnDiscard(Object.class, DefaultJsonStreamMapper::discardSegment)
                        .map(OutputSegment::take), WriteSessionResource::close);
    }

    @Override
    public Flux<byte[]> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return writeEventChunks(events, options, ByteArrayAllocator.INSTANCE);
    }

    @Override
    public <B> Flux<B> writeEventChunks(Publisher<? extends JsonEvent> events, JsonWriteOptions options,
                                       JsonBufferAllocator<B> allocator) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(allocator, "allocator");
        if (options.format() != JsonWriteOptions.Format.SINGLE_VALUE) {
            throw new IllegalArgumentException("Structural events require SINGLE_VALUE output");
        }
        return Flux.using(() -> new WriteSessionResource<>(mapper, options, true, allocator), session ->
                Flux.concat(Flux.defer(session::start),
                        Flux.from(events).concatMap(session::event, 0),
                        Flux.defer(session::finish)).doOnDiscard(Object.class, DefaultJsonStreamMapper::discardSegment)
                        .map(OutputSegment::take), WriteSessionResource::close);
    }

    @Override
    public Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options) {
        return writeBytes(values, options).map(DefaultJsonStreamMapper::readOnlyBuffer);
    }

    @Override
    public Flux<ByteBuffer> writeEventBuffers(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return writeEvents(events, options).map(DefaultJsonStreamMapper::readOnlyBuffer);
    }

    private static ByteBuffer readOnlyBuffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    private static void discardSegment(Object value) {
        if (value instanceof OutputSegment<?> segment) {
            segment.close();
        }
    }
}
