package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/** Equal-document CPU/allocation comparison, excluding source generation and network I/O. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class NettyStreamBenchmark {
    @Param({"2", "3"}) public String engine;
    @Param({"readBytes", "readBuffers", "readNetty", "writeBytes", "writeNetty"}) public String scenario;
    @Param({"false", "true"}) public boolean direct;
    @Param({"20", "4096"}) public int textSize;
    @Param({"8192"}) public int chunkSize;
    public JsonStreamMapper ordinary;
    public NettyJsonStreamMapper netty;
    public PooledByteBufAllocator allocator;
    public ByteBuf[] pooled;
    public ByteBuffer[] buffers;
    public byte[][] arrays;
    public Map<String, Object> value;
    public int records;
    public long expectedBytes;
    private final JsonType<Map<String, Object>> type = new JsonType<>() { };
    private final JsonReadOptions readOptions = JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);

    @Setup(Level.Trial)
    public void setup() {
        JsonMapper mapper = Json.builder().provider(engine.equals("2")
                ? new Jackson2Provider() : new Jackson3Provider()).build();
        ordinary = JsonStreamMapper.of(mapper);
        allocator = new PooledByteBufAllocator(direct);
        netty = NettyJsonStreamMapper.of(mapper, allocator);
        value = Map.of("n", 1, "s", "x".repeat(textSize));
        records = textSize > 1000 ? 256 : 1000;
        String row = mapper.write(value);
        byte[] document = ("[" + (row + ",").repeat(records - 1) + row + "]").getBytes(StandardCharsets.UTF_8);
        expectedBytes = document.length;
        arrays = new byte[(document.length + chunkSize - 1) / chunkSize][];
        pooled = new ByteBuf[arrays.length];
        buffers = new ByteBuffer[arrays.length];
        for (int i = 0; i < arrays.length; i++) {
            arrays[i] = Arrays.copyOfRange(document, i * chunkSize, Math.min(document.length, (i + 1) * chunkSize));
            pooled[i] = allocator.buffer(arrays[i].length).writeBytes(arrays[i]);
            buffers[i] = pooled[i].nioBuffer();
        }
        run();
    }

    @Benchmark
    public long run() {
        if (scenario.startsWith("read")) {
            Counter<Map<String, Object>> result = new Counter<>() {
                @Override
                long measure(Map<String, Object> item) {
                    return ((Number) item.get("n")).longValue();
                }
            };
            switch (scenario) {
                case "readBytes" -> ordinary.readBytes(Flux.fromArray(arrays), type, readOptions).subscribe(result);
                case "readBuffers" -> ordinary.readBuffers(Flux.fromArray(buffers), type, readOptions).subscribe(result);
                case "readNetty" -> netty.read(Flux.fromArray(pooled), type, readOptions).subscribe(result);
                default -> throw new AssertionError(scenario);
            }
            return result.verify(records);
        }
        JsonWriteOptions options = new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, chunkSize, 8 * 1024 * 1024);
        Flux<Map<String, Object>> values = Flux.range(0, records).map(i -> value);
        if (scenario.equals("writeBytes")) {
            Counter<byte[]> result = new Counter<>() {
                @Override
                long measure(byte[] item) {
                    return item.length;
                }
            };
            ordinary.writeBytes(values, options).subscribe(result);
            return result.verify(expectedBytes);
        }
        Counter<ByteBuf> result = new Counter<>() {
            @Override
            long measure(ByteBuf item) {
                try {
                    return item.readableBytes();
                } finally {
                    item.release();
                }
            }
        };
        netty.write(values, options).subscribe(result);
        return result.verify(expectedBytes);
    }

    @TearDown(Level.Trial)
    public void close() {
        for (ByteBuf buffer : pooled) {
            if (buffer.refCnt() != 1) {
                throw new AssertionError("Input ownership mismatch");
            }
            buffer.release();
        }
        allocator.trimCurrentThreadCache();
        if (allocator.pinnedDirectMemory() + allocator.pinnedHeapMemory() != 0) {
            throw new AssertionError("Live pooled buffer at teardown");
        }
    }

    private abstract static class Counter<T> extends BaseSubscriber<T> {
        private long sum;
        private boolean done;
        private Throwable failure;

        @Override
        protected final void hookOnNext(T value) {
            sum += measure(value);
        }

        abstract long measure(T value);

        @Override
        protected final void hookOnComplete() {
            done = true;
        }

        @Override
        protected final void hookOnError(Throwable error) {
            failure = error;
        }

        private long verify(long expected) {
            if (failure != null || !done || sum != expected) {
                throw new AssertionError("Incorrect benchmark result: " + sum + "/" + expected, failure);
            }
            return sum;
        }
    }
}
