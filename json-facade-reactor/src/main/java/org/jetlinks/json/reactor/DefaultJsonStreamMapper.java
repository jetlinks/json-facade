package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

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
        return Flux.using(() -> new ReadSessionResource<>(factory.get()), session ->
                input.concatMap(chunk -> {
                    session.feed(chunk);
                    return drain(session, options);
                }, 0).concatWith(Flux.defer(() -> {
                    session.endInput();
                    return drain(session, options);
                })), ReadSessionResource::close);
    }

    private static <T> Flux<T> drain(ReadSessionResource<T> session, JsonReadOptions options) {
        return Flux.generate(sink -> emit(session, options, sink));
    }

    private static <T> void emit(ReadSessionResource<T> session, JsonReadOptions options, SynchronousSink<T> sink) {
        while (!session.cancelled()) {
            JsonReadSession.Result<T> result = session.read();
            if (result.state() != JsonReadSession.State.VALUE) {
                sink.complete();
                return;
            }
            if (result.value() != null) {
                sink.next(result.value());
                return;
            }
            if (options.nullPolicy() == JsonReadOptions.NullPolicy.FAIL) {
                sink.error(new JsonException(JsonException.Kind.BINDING, "Reactor cannot emit a Java null record"));
                return;
            }
        }
        sink.complete();
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
                Flux.concat(start(session),
                        Flux.from(values).concatMap(value -> {
                            session.encode(value);
                            return drainOutput(session);
                        }, 0), finish(session)), WriteSessionResource::close);
    }

    @Override
    public Flux<byte[]> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(options, "options");
        if (options.format() != JsonWriteOptions.Format.SINGLE_VALUE) {
            throw new IllegalArgumentException("Structural events require SINGLE_VALUE output");
        }
        return Flux.using(() -> new WriteSessionResource(mapper, options, true), session ->
                Flux.concat(start(session),
                        Flux.from(events).concatMap(event -> {
                            session.event(event);
                            return drainOutput(session);
                        }, 0), finish(session)), WriteSessionResource::close);
    }

    private static Flux<byte[]> start(WriteSessionResource session) {
        return Flux.defer(() -> {
            session.start();
            return drainOutput(session);
        });
    }

    private static Flux<byte[]> finish(WriteSessionResource session) {
        return Flux.defer(() -> {
            session.finish();
            return drainOutput(session);
        });
    }

    private static Flux<byte[]> drainOutput(WriteSessionResource session) {
        return Flux.generate(session::emit);
    }

    @Override
    public Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options) {
        return writeBytes(values, options).map(bytes -> ByteBuffer.wrap(bytes).asReadOnlyBuffer());
    }

    @Override
    public Flux<ByteBuffer> writeEventBuffers(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return writeEvents(events, options).map(bytes -> ByteBuffer.wrap(bytes).asReadOnlyBuffer());
    }
}
