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
    public Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(options, "options");
        return Flux.using(() -> new WriteSessionResource(mapper, options, false), session ->
                Flux.concat(Flux.defer(session::start),
                        Flux.from(values).concatMap(session::encode, 0),
                        Flux.defer(session::finish)), WriteSessionResource::close);
    }

    @Override
    public Flux<byte[]> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(options, "options");
        if (options.format() != JsonWriteOptions.Format.SINGLE_VALUE) {
            throw new IllegalArgumentException("Structural events require SINGLE_VALUE output");
        }
        return Flux.using(() -> new WriteSessionResource(mapper, options, true), session ->
                Flux.concat(Flux.defer(session::start),
                        Flux.from(events).concatMap(session::event, 0),
                        Flux.defer(session::finish)), WriteSessionResource::close);
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
}
