package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class StreamBenchmark {
    @Param({"2", "3"}) public String engine;
    @Param({"readSession8k", "readBytes8k", "readBuffers8k", "readBytes64", "readNdjson8k",
            "readArray8k", "readPointer8k", "writeNdjson31", "writeNdjson4k", "writeNdjson1m",
            "writeArray31", "writeArray4k", "writeArray1m", "writeNumberTokens"})
    public String scenario;
    public JsonMapper mapper;
    public JsonStreamMapper streams;
    public JsonType<Map<String, Object>> type;
    public JsonReadOptions readOptions;
    public JsonWriteOptions writeOptions;
    public byte[][] chunks;
    public ByteBuffer[] buffers;
    public Map<String, String> value;
    public Flux<JsonEvent> numberEvents;
    public int records;
    public long recordBytes;

    @Setup(Level.Trial)
    public void setup() {
        mapper = Json.builder().provider(engine.equals("2") ? new Jackson2Provider() : new Jackson3Provider()).build();
        streams = JsonStreamMapper.of(mapper);
        type = JsonType.mapOf(JsonType.of(String.class), JsonType.of(Object.class));
        records = 1000;
        readOptions = JsonReadOptions.of(JsonReadOptions.Mode.ROOT_VALUES);
        writeOptions = JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON);
        if (scenario.equals("writeNumberTokens")) {
            String number = "-12345.6700e+8";
            JsonEvent[] events = new JsonEvent[records];
            Arrays.fill(events, JsonEvent.number(number));
            numberEvents = Flux.concat(Flux.just(JsonEvent.START_ARRAY), Flux.fromArray(events),
                    Flux.just(JsonEvent.END_ARRAY));
            recordBytes = number.length();
            writeOptions = JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE);
        } else if (scenario.startsWith("read")) {
            String record = "{\"id\":123,\"name\":\"test\",\"active\":true,\"items\":[1,2,3]}";
            String document;
            if (scenario.equals("readArray8k") || scenario.equals("readPointer8k")) {
                document = "[" + (record + ",").repeat(records - 1) + record + "]";
                if (scenario.equals("readPointer8k")) {
                    document = "{\"meta\":{\"skip\":1},\"items\":" + document + ",\"tail\":{\"skip\":2}}";
                    readOptions = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                            new JsonReadOptions.Selection(JsonPointer.of("/items"), true),
                            JsonReadOptions.NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
                } else {
                    readOptions = JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);
                }
            } else {
                document = (record + "\n").repeat(records);
                if (scenario.equals("readNdjson8k")) {
                    readOptions = JsonReadOptions.of(JsonReadOptions.Mode.NDJSON);
                }
            }
            byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
            int size = scenario.equals("readBytes64") ? 64 : 8192;
            chunks = new byte[(bytes.length + size - 1) / size][];
            buffers = new ByteBuffer[chunks.length];
            for (int i = 0; i < chunks.length; i++) {
                chunks[i] = Arrays.copyOfRange(bytes, i * size, Math.min(bytes.length, (i + 1) * size));
                buffers[i] = ByteBuffer.wrap(chunks[i]);
            }
        } else {
            int size = scenario.endsWith("1m") ? 1024 * 1024 : scenario.endsWith("4k") ? 4096 : 20;
            records = size > 100000 ? 8 : size > 1000 ? 256 : 1000;
            value = Map.of("text", "x".repeat(size));
            recordBytes = mapper.writeBytes(value).length;
            if (scenario.startsWith("writeArray")) {
                writeOptions = JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY);
            }
        }
        run();
    }

    @Benchmark
    public long run() {
        if (scenario.equals("readSession8k")) {
            return readSession();
        }
        if (scenario.startsWith("read")) {
            Counter<Map<String, Object>> counter = new Counter<>(false);
            if (scenario.equals("readBuffers8k")) {
                streams.readBuffers(Flux.fromArray(buffers), type, readOptions).subscribe(counter);
            } else {
                streams.readBytes(Flux.fromArray(chunks), type, readOptions).subscribe(counter);
            }
            counter.verify(records);
            if (counter.sum != records * 123L) throw new AssertionError("Read checksum " + counter.sum);
            return counter.sum;
        }
        Counter<byte[]> counter = new Counter<>(true);
        if (numberEvents != null) {
            streams.writeEvents(numberEvents, writeOptions).subscribe(counter);
        } else {
            streams.writeBytes(Flux.range(0, records).map(i -> value), writeOptions).subscribe(counter);
        }
        counter.verify(-1);
        long expected = records * (recordBytes + 1)
                + (numberEvents != null || writeOptions.format() == JsonWriteOptions.Format.ARRAY ? 1 : 0);
        if (counter.sum != expected) {
            throw new AssertionError("Write byte count");
        }
        return counter.sum;
    }

    private long readSession() {
        long sum = 0;
        int count = 0;
        try (JsonReadSession<Map<String, Object>> session = mapper.readSession(type, readOptions)) {
            for (byte[] chunk : chunks) {
                session.feed(ByteBuffer.wrap(chunk));
                while (true) {
                    var result = session.read();
                    if (result.state() != JsonReadSession.State.VALUE) break;
                    count++;
                    sum += ((Number) result.value().get("id")).longValue();
                }
            }
            session.endInput();
            while (true) {
                var result = session.read();
                if (result.state() == JsonReadSession.State.END) break;
                if (result.state() != JsonReadSession.State.VALUE) throw new AssertionError("EOF");
                count++;
                sum += ((Number) result.value().get("id")).longValue();
            }
        }
        if (count != records || sum != records * 123L) throw new AssertionError("Session checksum");
        return sum;
    }

    public static final class Counter<T> implements Subscriber<T> {
        final boolean bytes;
        public long sum;
        public int signals;
        boolean complete;
        Throwable error;
        Counter(boolean bytes) { this.bytes = bytes; }
        @Override public void onSubscribe(Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(T value) {
            signals++;
            sum += bytes ? ((byte[]) value).length : ((Number) ((Map<?, ?>) value).get("id")).longValue();
        }
        @Override public void onError(Throwable failure) { error = failure; }
        @Override public void onComplete() { complete = true; }
        void verify(int count) {
            if (error != null) throw new AssertionError(error);
            if (!complete || (count >= 0 && signals != count)) throw new AssertionError("Completion/signals");
        }
    }
}
