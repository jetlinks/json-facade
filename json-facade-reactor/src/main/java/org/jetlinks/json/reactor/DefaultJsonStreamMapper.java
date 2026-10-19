package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
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
        return Flux.using(() -> new SessionGuard<>(mapper.readSession(type, options)), session ->
                input.concatMap(chunk -> {
                    session.feed(chunk);
                    return drain(session, options);
                }, 0).concatWith(Flux.defer(() -> {
                    session.endInput();
                    return drain(session, options);
                })), SessionGuard::close);
    }

    private static <T> Flux<T> drain(SessionGuard<T> session, JsonReadOptions options) {
        return Flux.generate(sink -> emit(session, options, sink));
    }

    private static <T> void emit(SessionGuard<T> session, JsonReadOptions options, SynchronousSink<T> sink) {
        while (true) {
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
    }

    @Override
    public Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options) {
        Objects.requireNonNull(options, "options");
        return Flux.defer(() -> {
            WriteState state = new WriteState(options.format() == JsonWriteOptions.Format.ARRAY);
            Flux<byte[]> records = Flux.from(values).concatMap(value -> encode(value, options, state), 0);
            if (!state.array) {
                return records;
            }
            return Flux.concat(Flux.just(new byte[]{'['}), records, Flux.just(new byte[]{']'}));
        });
    }

    private Flux<byte[]> encode(Object value, JsonWriteOptions options, WriteState state) {
        return Flux.defer(() -> {
            int limit = Math.min(options.maxValueBytes(), mapper.options().maxValueBytes());
            BoundedJsonOutput output = new BoundedJsonOutput(limit);
            mapper.write(value, output);
            byte[] record = output.bytes();
            Flux<byte[]> chunks = chunks(record, options.chunkSize());
            if (state.array) {
                boolean first = state.first;
                state.first = false;
                return first ? chunks : Flux.concat(Flux.just(new byte[]{','}), chunks);
            }
            return chunks.concatWithValues(new byte[]{'\n'});
        });
    }

    private static Flux<byte[]> chunks(byte[] bytes, int size) {
        return Flux.generate(() -> 0, (offset, sink) -> {
            if (offset == bytes.length) {
                sink.complete();
                return offset;
            }
            int end = (int) Math.min((long) bytes.length, (long) offset + size);
            sink.next(Arrays.copyOfRange(bytes, offset, end));
            return end;
        });
    }

    @Override
    public Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options) {
        return writeBytes(values, options).map(bytes -> ByteBuffer.wrap(bytes).asReadOnlyBuffer());
    }

    private static final class WriteState {
        private final boolean array;
        private boolean first = true;

        private WriteState(boolean array) {
            this.array = array;
        }
    }

    /** Serializes cancellation cleanup with synchronous CPU parsing, without scheduling or background I/O. */
    private static final class SessionGuard<T> implements AutoCloseable {
        private final JsonReadSession<T> session;

        private SessionGuard(JsonReadSession<T> session) {
            this.session = session;
        }

        private synchronized void feed(ByteBuffer input) {
            session.feed(input);
        }

        private synchronized JsonReadSession.Result<T> read() {
            return session.read();
        }

        private synchronized void endInput() {
            session.endInput();
        }

        @Override
        public synchronized void close() {
            session.close();
        }
    }
}
