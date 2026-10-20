package org.jetlinks.json.netty;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/** Independent bounded-memory Netty acceptance fixture; never accumulates a whole document.
 * Input is generated directly into pooled buffers. Output is verified by a separate native parser.
 */
public final class NettyStreamingStress {
    private static final Map<String, Object> VALUE = Map.of("n", 1, "s", "x".repeat(1000));

    private NettyStreamingStress() {
    }

    public static void main(String[] args) throws Exception {
        String engine = args[0];
        String mode = args[1];
        long target = Long.parseLong(args[2]) * 1024 * 1024;
        int chunk = Integer.parseInt(args[3]);
        boolean direct = Boolean.parseBoolean(args[4]);
        PooledByteBufAllocator allocator = new PooledByteBufAllocator(direct);
        JsonMapper mapper = Json.builder().provider(engine.equals("2")
                ? new Jackson2Provider() : new Jackson3Provider()).build();
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, allocator);
        byte[] row = mapper.writeBytes(VALUE);
        long records = target / (row.length + 1) + 1;
        GeneratedInput generated = new GeneratedInput(row, records, chunk,
                mode.equals("document-copy") || mode.equals("selected-read"), allocator);
        Flux<ByteBuf> input = Flux.<ByteBuf>generate(sink -> {
            ByteBuf next = generated.next();
            if (next == null) {
                sink.complete();
            } else {
                sink.next(next);
            }
        }).doOnDiscard(ByteBuf.class, ByteBuf::release);
        long allocated = allocated();
        long start = System.nanoTime();
        Counter<?> check;
        long bytes;
        if (mode.endsWith("read")) {
            Counter<Map<String, Object>> result = new Counter<>() {
                @Override
                void accept(Map<String, Object> value) {
                    require(VALUE.equals(value), "Incorrect bound record");
                    count++;
                }
            };
            JsonReadOptions options = mode.equals("selected-read") ? new JsonReadOptions(
                    JsonReadOptions.Mode.SINGLE_VALUE, new JsonReadOptions.Selection(JsonPointer.of("/items"), true),
                    JsonReadOptions.NullPolicy.FAIL, 8192) : JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);
            streams.readOwned(input, new JsonType<Map<String, Object>>() { }, options).subscribe(result);
            check = result;
            bytes = generated.bytes;
        } else {
            try (NativeCounter result = new NativeCounter(mode.equals("document-copy"))) {
                if (mode.equals("document-copy")) {
                    streams.writeEvents(streams.readOwnedEvents(input), new JsonWriteOptions(
                            JsonWriteOptions.Format.SINGLE_VALUE, chunk, 8192)).subscribe(result);
                } else {
                    Flux<Map<String, Object>> values = Flux.generate(() -> 0L, (index, sink) -> {
                        if (index == records) {
                            sink.complete();
                        } else {
                            sink.next(VALUE);
                        }
                        return index + 1;
                    });
                    streams.write(values, new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, chunk, 8192))
                            .subscribe(result);
                }
                result.finish();
                check = result;
                bytes = result.bytes;
            }
        }
        check.verify(records);
        require(bytes >= target, "Logical document below target");
        allocator.trimCurrentThreadCache();
        long pinned = allocator.pinnedDirectMemory() + allocator.pinnedHeapMemory();
        require(pinned == 0, "Live pooled references: " + pinned);
        System.out.printf("{\"engine\":\"%s\",\"mode\":\"%s\",\"direct\":%s,\"chunk\":%d,"
                        + "\"bytes\":%d,\"records\":%d,\"millis\":%.3f,\"allocatedBytes\":%d,"
                        + "\"pinnedBytes\":%d,\"reservedDirectBytes\":%d,\"reservedHeapBytes\":%d}%n",
                engine, mode, direct, chunk, bytes, records, (System.nanoTime() - start) / 1_000_000.0,
                allocated() - allocated, pinned, allocator.metric().usedDirectMemory(), allocator.metric().usedHeapMemory());
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private abstract static class Counter<T> extends BaseSubscriber<T> {
        long count;
        private Throwable failure;
        private boolean done;

        @Override
        protected final void hookOnNext(T value) {
            accept(value);
            request(1);
        }

        @Override
        protected final void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
            request(1);
        }

        abstract void accept(T value);

        @Override
        protected final void hookOnError(Throwable error) {
            failure = error;
        }

        @Override
        protected final void hookOnComplete() {
            done = true;
        }

        void verify(long expected) {
            if (failure != null) {
                throw new AssertionError(failure);
            }
            require(done && count == expected, "Incomplete stream: " + count + "/" + expected);
        }
    }

    private static final class NativeCounter extends Counter<ByteBuf> implements AutoCloseable {
        private final JsonParser parser;
        private final ByteBufferFeeder feeder;
        private final boolean wrapped;
        private long texts;
        private boolean tail;
        private long bytes;

        private NativeCounter(boolean wrapped) throws java.io.IOException {
            this.wrapped = wrapped;
            parser = new JsonFactory().createNonBlockingByteBufferParser();
            feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
        }

        @Override
        void accept(ByteBuf buffer) {
            try {
                bytes += buffer.readableBytes();
                for (ByteBuffer view : buffer.nioBuffers()) {
                    feeder.feedInput(view);
                    drain();
                }
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            } finally {
                buffer.release();
            }
        }

        private void drain() throws java.io.IOException {
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                if (token == JsonToken.VALUE_NUMBER_INT && "n".equals(parser.currentName())) {
                    require(parser.getIntValue() == 1, "Invalid numeric field");
                    count++;
                } else if (token == JsonToken.VALUE_STRING) {
                    require(VALUE.get("s").equals(parser.getText()), "Invalid string field");
                    texts++;
                } else if (token == JsonToken.VALUE_TRUE && "tail".equals(parser.currentName())) {
                    tail = true;
                }
            }
        }

        private void finish() throws java.io.IOException {
            feeder.endOfInput();
            drain();
            require(parser.isClosed(), "Native output parser did not reach EOF");
            require(texts == count && (!wrapped || tail), "Missing strings/trailing metadata");
        }

        @Override
        public void close() throws java.io.IOException {
            parser.close();
        }
    }

    private static final class GeneratedInput {
        private final byte[][] parts;
        private final int chunk;
        private final PooledByteBufAllocator allocator;
        private long repeated;
        private int part;
        private int offset;
        private long bytes;

        private GeneratedInput(byte[] row, long records, int chunk, boolean wrapped, PooledByteBufAllocator allocator) {
            this.chunk = chunk;
            this.allocator = allocator;
            repeated = records - 1;
            byte[] delimited = java.util.Arrays.copyOf(row, row.length + 1);
            delimited[row.length] = ',';
            parts = new byte[][]{(wrapped ? "{\"items\":[" : "[").getBytes(StandardCharsets.UTF_8),
                    delimited, row, (wrapped ? "],\"tail\":true}" : "]").getBytes(StandardCharsets.UTF_8)};
        }

        private ByteBuf next() {
            if (part == parts.length) {
                return null;
            }
            ByteBuf buffer = allocator.buffer(chunk, chunk);
            while (buffer.isWritable() && part < parts.length) {
                if (part == 1 && repeated == 0) {
                    part++;
                }
                byte[] current = parts[part];
                int size = Math.min(buffer.writableBytes(), current.length - offset);
                buffer.writeBytes(current, offset, size);
                bytes += size;
                offset += size;
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
