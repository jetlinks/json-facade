package org.jetlinks.json;

import java.util.ArrayList;
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

/** Opt-in borrowed/copy creation and access comparison over existing JSON-compatible containers.
 * Source construction, mapper setup and Pointer compilation are outside measured operations.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TreeWrapBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"1", "1000"})
    public int size;
    @Param({"object", "array"})
    public String shape;
    private JsonMapper mapper;
    private Map<String, ?> object;
    private List<?> array;
    private JsonValue borrowed;
    private JsonValue copied;
    private JsonPointer pointer;

    @Setup
    public void setup() {
        mapper = Json.builder().provider(engine).build();
        List<Object> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(Map.of("n", i));
        }
        array = items;
        object = Map.of("items", items);
        pointer = JsonPointer.of((shape.equals("object") ? "/items" : "") + "/0/n");
        borrowed = wrap();
        copied = copy();
        if (borrowed.at(pointer).asInt() != 0 || copied.at(pointer).asInt() != 0
                || !borrowed.toJavaValue().equals(copied.toJavaValue())) {
            throw new AssertionError("Unexpected tree contents");
        }
    }

    @Benchmark
    public JsonValue wrap() {
        return shape.equals("object") ? mapper.wrap(object) : mapper.wrap(array);
    }

    @Benchmark
    public JsonValue copy() {
        return shape.equals("object") ? mapper.createObject(object) : mapper.createArray(array);
    }

    @Benchmark
    public JsonValue borrowedView() {
        return borrowed.at(pointer);
    }

    @Benchmark
    public JsonValue copiedView() {
        return copied.at(pointer);
    }
}
