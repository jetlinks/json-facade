package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 同配置、绑定类型、输入所有权和数组/EOF 校验；大数组通过 records/textSize 参数开启。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ArrayIteratorBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"1", "1000"})
    public int records;
    @Param({"integer"})
    public String payload;
    @Param({"20"})
    public int textSize;
    private JsonMapper mapper;
    private byte[] input;
    private JsonType<?> type;
    private com.fasterxml.jackson.databind.ObjectReader native2;
    private tools.jackson.databind.ObjectReader native3;
    private long expected;

    @Setup
    public void setup() throws Exception {
        type = switch (payload) {
            case "integer" -> JsonType.of(Integer.class);
            case "map" -> new JsonType<Map<String, Object>>() { };
            case "dto" -> JsonType.of(Row.class);
            case "nested" -> new JsonType<List<Map<String, Object>>>() { };
            default -> throw new IllegalArgumentException(payload);
        };
        if (engine.equals("jackson2")) {
            mapper = Json.builder().provider(engine).configureNative(
                    com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> {
                        var nativeMapper = builder.build();
                        native2 = nativeMapper.readerFor(nativeMapper.constructType(type.type()))
                                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                    })
                    .build();
        } else {
            mapper = Json.builder().provider(engine).configureNative(
                    tools.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> {
                        var nativeMapper = builder.build();
                        native3 = nativeMapper.readerFor(nativeMapper.constructType(type.type()))
                                .without(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                    })
                    .build();
        }
        String row = payload.equals("integer") ? "1234"
                : "{\"id\":123,\"text\":\"" + "x".repeat(textSize) + "\",\"active\":true}";
        if (payload.equals("nested")) row = "[" + row + "]";
        input = ("[" + (row + ",").repeat(records - 1) + row + "]").getBytes(StandardCharsets.UTF_8);
        expected = records * (payload.equals("integer") ? 1234L : 123L + textSize);
        if (cursor() != expected || iterator() != expected || nativeCursor() != expected) {
            throw new AssertionError("Incorrect array traversal");
        }
    }

    @Benchmark
    public long nativeCursor() throws Exception {
        long sum = 0;
        int count = 0;
        if (native2 != null) {
            try (var parser = native2.createParser(new ByteArrayInputStream(input))) {
                if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) throw new AssertionError();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null) throw new AssertionError("Incomplete array");
                    sum += observe(native2.readValue(parser));
                    count++;
                }
                if (parser.nextToken() != null) throw new AssertionError("Trailing input");
            }
        } else {
            try (var parser = native3.createParser(new ByteArrayInputStream(input))) {
                if (parser.nextToken() != tools.jackson.core.JsonToken.START_ARRAY) throw new AssertionError();
                while (parser.nextToken() != tools.jackson.core.JsonToken.END_ARRAY) {
                    if (parser.currentToken() == null) throw new AssertionError("Incomplete array");
                    sum += observe(native3.readValue(parser));
                    count++;
                }
                if (parser.nextToken() != null) throw new AssertionError("Trailing input");
            }
        }
        return verify(sum, count);
    }

    @Benchmark
    public long cursor() {
        try (JsonReader reader = mapper.reader(new ByteArrayInputStream(input))) {
            if (reader.next() != JsonToken.START_ARRAY) {
                throw new AssertionError("Expected array");
            }
            long sum = 0;
            int count = 0;
            JsonToken token;
            while ((token = reader.next()) != JsonToken.END_ARRAY) {
                if (token == null) {
                    throw new AssertionError("Incomplete array");
                }
                sum += observe(reader.readValue(type));
                count++;
            }
            if (reader.next() != null || count != records) {
                throw new AssertionError("Unexpected input");
            }
            return verify(sum, count);
        }
    }

    @Benchmark
    public long iterator() {
        try (CloseableIterator<?> iterator = mapper.iterateArray(new ByteArrayInputStream(input), type)) {
            long sum = 0;
            int count = 0;
            while (iterator.hasNext()) {
                sum += observe(iterator.next());
                count++;
            }
            if (count != records) {
                throw new AssertionError("Unexpected input");
            }
            return verify(sum, count);
        }
    }

    private long observe(Object value) {
        if (payload.equals("integer")) return ((Integer) value).longValue();
        if (value instanceof Row row) {
            if (!row.active()) throw new AssertionError("Boolean mismatch");
            return row.id() + row.text().length();
        }
        if (value instanceof List<?> nested) value = nested.get(0);
        Map<?, ?> row = (Map<?, ?>) value;
        if (!Boolean.TRUE.equals(row.get("active"))) throw new AssertionError("Boolean mismatch");
        return ((Number) row.get("id")).longValue() + ((String) row.get("text")).length();
    }

    private long verify(long sum, int count) {
        if (count != records || sum != expected) throw new AssertionError("Count/checksum mismatch");
        return sum;
    }

    public record Row(int id, String text, boolean active) { }
}
