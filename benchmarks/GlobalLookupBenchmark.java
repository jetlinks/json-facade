package org.jetlinks.json;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Published default lookup versus an already configured reference; initialization is outside measurement. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class GlobalLookupBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    private JsonMapper mapper;

    @Setup
    public void setup() {
        mapper = Json.builder().provider(engine).build();
        Json.registerGlobal(mapper);
        if (Json.global() != mapper) {
            throw new AssertionError("Unexpected default mapper");
        }
    }

    @Benchmark
    public JsonMapper published() {
        return Json.global();
    }

    @Benchmark
    public JsonMapper direct() {
        return mapper;
    }
}
