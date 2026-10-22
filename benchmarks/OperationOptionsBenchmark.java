package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import reactor.core.publisher.Flux;

/** 区分准备成本与复用成本；配置模式使用同一有效负载和相同字节结果。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class OperationOptionsBenchmark {
    private static final JsonAttribute<String> ATTRIBUTE = JsonAttribute.of("mode", String.class);
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"default", "attribute", "nulls"})
    public String configuration;
    @Param({"1", "1000"})
    public int records;
    private JsonMapper base;
    private JsonMapper operation;
    private JsonOperationOptions options;
    private JsonStreamMapper streams;
    private final JsonType<Object> type = JsonType.of(Object.class);
    private final JsonReadOptions framing = JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);
    private final JsonWriteOptions output = JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY);
    private List<Map<String, Object>> value;
    private byte[] bytes;

    @Setup
    public void setup() {
        base = Json.builder().provider(engine).build();
        options = switch (configuration) {
            case "attribute" -> JsonOperationOptions.builder().attribute(ATTRIBUTE, "custom").build();
            case "nulls" -> JsonOperationOptions.builder().omitNulls(false).build();
            default -> JsonOperationOptions.EMPTY;
        };
        operation = base.withOptions(options);
        streams = JsonStreamMapper.of(operation);
        value = Collections.nCopies(records, Map.of("id", 1234, "name", "sensor", "active", true));
        bytes = base.writeBytes(value);
        if (!java.util.Arrays.equals(bytes, write()) || !value.equals(read()) || !value.equals(convert())
                || cursor() != records || session() != records || streamWrite() != bytes.length) {
            throw new AssertionError("Operation configuration changed the benchmark workload");
        }
    }

    @Benchmark
    public JsonMapper prepare() {
        return base.withOptions(options);
    }

    @Benchmark
    public Object read() {
        return operation.read(bytes, type);
    }

    @Benchmark
    public byte[] write() {
        return operation.writeBytes(value);
    }

    @Benchmark
    public Object convert() {
        return operation.convert(value, type);
    }

    @Benchmark
    public int cursor() {
        try (var iterator = operation.iterateArray(new ByteArrayInputStream(bytes), type)) {
            int count = 0;
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            return count;
        }
    }

    @Benchmark
    public int session() {
        try (var session = operation.readSession(type, framing)) {
            session.feed(ByteBuffer.wrap(bytes));
            session.endInput();
            int count = 0;
            while (session.read().state() == JsonReadSession.State.VALUE) {
                count++;
            }
            return count;
        }
    }

    @Benchmark
    public int streamWrite() {
        return streams.writeBytes(Flux.fromIterable(value), output).map(chunk -> chunk.length).reduce(0, Integer::sum)
                .block();
    }
}
