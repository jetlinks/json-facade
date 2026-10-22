package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Equal binding, array/EOF validation and input ownership for cursor and iterator traversal. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ArrayIteratorBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"1", "1000"})
    public int records;
    private JsonMapper mapper;
    private byte[] input;
    private final JsonType<Integer> type = JsonType.of(Integer.class);

    @Setup
    public void setup() {
        mapper = Json.builder().provider(engine).build();
        input = ("[" + "1234,".repeat(records - 1) + "1234]").getBytes(StandardCharsets.UTF_8);
        if (cursor() != records * 1234L || iterator() != records * 1234L) {
            throw new AssertionError("Incorrect array traversal");
        }
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
                sum += reader.readValue(type);
                count++;
            }
            if (reader.next() != null || count != records) {
                throw new AssertionError("Unexpected input");
            }
            return sum;
        }
    }

    @Benchmark
    public long iterator() {
        try (CloseableIterator<Integer> iterator = mapper.iterateArray(new ByteArrayInputStream(input), type)) {
            long sum = 0;
            int count = 0;
            while (iterator.hasNext()) {
                sum += iterator.next();
                count++;
            }
            if (count != records) {
                throw new AssertionError("Unexpected input");
            }
            return sum;
        }
    }
}
