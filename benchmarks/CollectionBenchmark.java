package org.jetlinks.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.compat.fastjson.JSON;
import org.jetlinks.json.compat.fastjson.JSONObject;
import org.jetlinks.json.fastjson2.Fastjson2Provider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 相同绑定配置下比较 JDK 容器、中立集合与迁移层，不把 Provider 本身成本归为树层成本。 */
@SuppressWarnings("deprecation")
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CollectionBenchmark {
    @Param({"jackson2", "jackson3", "fastjson2"})
    public String engine;
    @Param({"1", "1000"})
    public int size;
    private JsonMapper mapper;
    private String input;
    private Map<String, Object> jdk;
    private JsonObject neutral;
    private JSONObject legacy;
    private final Map<String, Object> payload = Map.of("values", List.of(1, 2, 3));

    @Setup
    public void setup() {
        var builder = Json.builder().provider(engine);
        if (engine.equals("fastjson2")) {
            builder.options(Fastjson2Provider.nativeOptions());
        }
        mapper = builder.build();
        List<Object> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rows.add(Map.of("id", i, "name", "item-" + i));
        }
        input = mapper.write(Map.of("count", size, "items", rows));
        jdk = mapper.read(input, JsonType.mapOf(JsonType.of(String.class), JsonType.of(Object.class)));
        neutral = mapper.readTree(input).asObject();
        legacy = JSON.parseObject(mapper, input);
        if (!jdk.equals(neutral) || !jdk.equals(legacy)
                || !mapper.read(mapper.write(legacy), Object.class).equals(jdk)) {
            throw new AssertionError("Collection representations differ");
        }
    }

    @Benchmark
    public Object jdkGet() { return jdk.get("count"); }

    @Benchmark
    public Object neutralGet() { return neutral.get("count"); }

    @Benchmark
    public Object legacyGet() { return legacy.get("count"); }

    @Benchmark
    public Object jdkNested() { return ((List<?>) jdk.get("items")).get(0); }

    @Benchmark
    public Object neutralNested() { return ((List<?>) neutral.get("items")).get(0); }

    @Benchmark
    public Object legacyNested() { return ((List<?>) legacy.get("items")).get(0); }

    @Benchmark
    public int nodeInt() { return neutral.node("count").asInt(); }

    @Benchmark
    public Object jdkInsert() { return jdk.put("payload", payload); }

    @Benchmark
    public Object neutralInsert() { return neutral.put("payload", payload); }

    @Benchmark
    public Object legacyInsert() { return legacy.put("payload", payload); }

    @Benchmark
    public byte[] jdkRoundTrip() { return mapper.writeBytes(mapper.read(input, Object.class)); }

    @Benchmark
    public byte[] neutralRoundTrip() { return mapper.writeBytes(mapper.readTree(input)); }

    @Benchmark
    public byte[] legacyRoundTrip() { return mapper.writeBytes(JSON.parseObject(mapper, input)); }
}
