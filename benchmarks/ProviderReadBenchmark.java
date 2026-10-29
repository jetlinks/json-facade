package org.jetlinks.json;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** Configured native and facade binding; input construction is outside measured operations. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProviderReadBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"1", "128"})
    public int rows;
    private JsonMapper facade;
    private com.fasterxml.jackson.databind.ObjectMapper native2;
    private tools.jackson.databind.ObjectMapper native3;
    private final JsonType<Object> type = JsonType.of(Object.class);
    private String document;
    private byte[] bytes;

    @Setup
    public void setup() throws Exception {
        if (engine.equals("jackson2")) {
            facade = Json.builder().provider(engine).configureNative(
                    com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> native2 = builder.build()).build();
        } else {
            facade = Json.builder().provider(engine).configureNative(
                    tools.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> native3 = builder.build()).build();
        }
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i + 1000);
            row.put("name", "sensor-设备-" + i);
            row.put("value", new BigDecimal(i + ".2500"));
            row.put("active", true);
            row.put("items", List.of(1, 2, 3));
            items.add(row);
        }
        Object expected = Map.of("items", items, "meta", Map.of("count", rows, "site", "factory-园区"));
        document = facade.write(expected);
        bytes = document.getBytes(StandardCharsets.UTF_8);
        if (!expected.equals(facadeBytes()) || !expected.equals(facadeString())
                || !expected.equals(nativeBytes()) || !expected.equals(nativeString())) {
            throw new AssertionError("Binding mismatch");
        }
    }

    @Benchmark
    public Object facadeBytes() {
        return facade.read(bytes, type);
    }

    @Benchmark
    public Object nativeBytes() throws Exception {
        return native2 != null ? native2.readValue(bytes, Object.class) : native3.readValue(bytes, Object.class);
    }

    @Benchmark
    public Object facadeString() {
        return facade.read(document, type);
    }

    /** Jackson 2 convenience reference has different raw-IO failure behavior; success workload only. */
    @Benchmark
    public Object nativeString() throws Exception {
        return native2 != null ? native2.readValue(document, Object.class) : native3.readValue(document, Object.class);
    }
}
