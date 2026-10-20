package org.jetlinks.json.spring;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import io.netty.buffer.PooledByteBufAllocator;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/** Standalone bounded-memory acceptance fixture with generated input and independent native validation.
 * Never collects a document; each subscriber requests one value/buffer at a time.
 */
public final class DataBufferStreamingStress {
    private static final Map<String, Object> VALUE = Map.of("n", 1, "s", "中😀" + "x".repeat(1000));

    private DataBufferStreamingStress() {
    }

    public static void main(String[] args) throws Exception {
        String kind = args[2];
        boolean direct = kind.endsWith("direct");
        PooledByteBufAllocator pool = kind.startsWith("netty") ? new PooledByteBufAllocator(direct) : null;
        DataBufferFactory factory = pool == null ? new DefaultDataBufferFactory(direct) : new NettyDataBufferFactory(pool);
        JsonMapper mapper = Json.builder().provider(args[0].equals("2")
                ? new Jackson2Provider() : new Jackson3Provider()).build();
        long start = System.nanoTime();
        String mode = args[1];
        long bytes = mode.equals("closed-resources") ? closedOwners(factory)
                : stream(mapper, factory, mode, Long.parseLong(args[3]) * 1024 * 1024, Integer.parseInt(args[4]));
        long pinned = pool == null ? 0 : pool.pinnedHeapMemory() + pool.pinnedDirectMemory();
        require(pinned == 0, "Unreleased pooled bytes: " + pinned);
        System.out.printf("{\"engine\":\"%s\",\"mode\":\"%s\",\"factory\":\"%s\",\"bytes\":%d,"
                        + "\"chunk\":%s,\"pinnedBytes\":%d,\"millis\":%.3f}%n",
                args[0], mode, kind, bytes, args[4], pinned, (System.nanoTime() - start) / 1_000_000.0);
    }

    private static long stream(JsonMapper mapper, DataBufferFactory factory, String mode, long target, int chunk)
            throws java.io.IOException {
        var streams = DataBufferJsonStreamMapper.of(mapper, factory);
        byte[] row = mapper.writeBytes(VALUE);
        long records = target / (row.length + 1) + 1;
        GeneratedInput generated = new GeneratedInput(factory, row, records, chunk,
                mode.equals("selected-read") || mode.equals("document-copy"));
        Flux<DataBuffer> input = Flux.<DataBuffer>generate(sink -> {
            DataBuffer next = generated.next();
            if (next == null) {
                sink.complete();
            } else {
                sink.next(next);
            }
        }).doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        if (mode.endsWith("read")) {
            JsonReadOptions options = mode.equals("selected-read") ? new JsonReadOptions(
                    JsonReadOptions.Mode.SINGLE_VALUE, new JsonReadOptions.Selection(JsonPointer.of("/items"), true),
                    JsonReadOptions.NullPolicy.FAIL, 8192) : JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);
            Counter<Map<String, Object>> counter = new Counter<>(value -> require(VALUE.equals(value), "Wrong record"));
            streams.read(input, new JsonType<Map<String, Object>>() { }, options).subscribe(counter);
            counter.verify(records);
            require(generated.bytes >= target, "Document below target");
            return generated.bytes;
        }
        try (NativeCheck check = new NativeCheck(mode.equals("document-copy"))) {
            Flux<DataBuffer> output = mode.equals("document-copy")
                    ? streams.writeEvents(streams.readEvents(input), new JsonWriteOptions(
                            JsonWriteOptions.Format.SINGLE_VALUE, chunk, 8192))
                    : streams.write(Flux.range(0, Math.toIntExact(records)).map(index -> VALUE),
                            new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, chunk, 8192));
            Counter<DataBuffer> counter = new Counter<>(check::accept);
            output.subscribe(counter);
            counter.verify(counter.count);
            check.finish(records);
            require(check.bytes >= target, "Output below target");
            return check.bytes;
        }
    }

    private static long closedOwners(DataBufferFactory factory) {
        List<DataBufferInputChunk> retained = new ArrayList<>();
        for (int i = 0; i < 192; i++) {
            DataBuffer buffer = factory.allocateBuffer(1024 * 1024);
            buffer.writePosition(1024 * 1024);
            DataBufferInputChunk chunk = new DataBufferInputChunk(buffer);
            chunk.nextBuffer();
            chunk.close();
            chunk.close();
            retained.add(chunk);
        }
        Reference.reachabilityFence(retained);
        return 192L * 1024 * 1024;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Counter<T> extends BaseSubscriber<T> {
        private final Consumer<T> consumer;
        private long count;
        private boolean done;
        private Throwable failure;

        private Counter(Consumer<T> consumer) {
            this.consumer = consumer;
        }

        @Override
        protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            request(1);
        }

        @Override
        protected void hookOnNext(T value) {
            consumer.accept(value);
            count++;
            request(1);
        }

        @Override
        protected void hookOnComplete() {
            done = true;
        }

        @Override
        protected void hookOnError(Throwable error) {
            failure = error;
        }

        private void verify(long expected) {
            if (failure != null) {
                throw new AssertionError(failure);
            }
            require(done && count == expected, "Incomplete stream: " + count + "/" + expected);
        }
    }

    private static final class NativeCheck implements AutoCloseable {
        private final JsonParser parser;
        private final ByteBufferFeeder feeder;
        private final boolean wrapped;
        private long numbers;
        private long texts;
        private long bytes;
        private boolean tail;

        private NativeCheck(boolean wrapped) throws java.io.IOException {
            this.wrapped = wrapped;
            parser = new JsonFactory().createNonBlockingByteBufferParser();
            feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
        }

        private void accept(DataBuffer buffer) {
            try (var views = buffer.readableByteBuffers()) {
                bytes += buffer.readableByteCount();
                while (views.hasNext()) {
                    feeder.feedInput(views.next());
                    drain();
                }
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        private void drain() throws java.io.IOException {
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                if (token == JsonToken.VALUE_NUMBER_INT && "n".equals(parser.currentName())) {
                    require(parser.getIntValue() == 1, "Wrong number");
                    numbers++;
                } else if (token == JsonToken.VALUE_STRING) {
                    require(VALUE.get("s").equals(parser.getText()), "Wrong string");
                    texts++;
                } else if (token == JsonToken.VALUE_TRUE && "tail".equals(parser.currentName())) {
                    tail = true;
                }
            }
        }

        private void finish(long records) throws java.io.IOException {
            feeder.endOfInput();
            drain();
            require(parser.isClosed() && numbers == records && texts == records && (!wrapped || tail),
                    "Incomplete output document");
        }

        @Override
        public void close() throws java.io.IOException {
            parser.close();
        }
    }

    private static final class GeneratedInput {
        private final DataBufferFactory factory;
        private final byte[][] parts;
        private final int chunk;
        private long repeated;
        private int part;
        private int offset;
        private long bytes;

        private GeneratedInput(DataBufferFactory factory, byte[] row, long records, int chunk, boolean wrapped) {
            this.factory = factory;
            this.chunk = chunk;
            repeated = records - 1;
            byte[] separated = java.util.Arrays.copyOf(row, row.length + 1);
            separated[row.length] = ',';
            parts = new byte[][]{(wrapped ? "{\"items\":[" : "[").getBytes(StandardCharsets.UTF_8), separated,
                    row, (wrapped ? "],\"tail\":true}" : "]").getBytes(StandardCharsets.UTF_8)};
        }

        private DataBuffer next() {
            if (part == parts.length) {
                return null;
            }
            DataBuffer buffer = factory.allocateBuffer(chunk);
            while (buffer.writePosition() < chunk && part < parts.length) {
                if (part == 1 && repeated == 0) {
                    part++;
                }
                byte[] current = parts[part];
                int length = Math.min(chunk - buffer.writePosition(), current.length - offset);
                buffer.write(current, offset, length);
                bytes += length;
                offset += length;
                if (offset == current.length) {
                    offset = 0;
                    if (part != 1 || --repeated == 0) {
                        part++;
                    }
                }
            }
            return buffer;
        }
    }
}
