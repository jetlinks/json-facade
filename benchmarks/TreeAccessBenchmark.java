package org.jetlinks.json;

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

/** Opt-in traversal/allocation comparison; Pointer construction is outside the measured operations. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TreeAccessBenchmark {
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"1", "4", "8"})
    public int depth;
    @Param({"object", "mixed"})
    public String shape;
    private JsonValue tree;
    private JsonValue parent;
    private JsonPointer pointer;
    private JsonPointer parentPointer;
    private final Integer scalar = Integer.valueOf(1234);
    private final Map<String, Object> payload = Map.of("items", List.of(1, 2, 3), "name", "sample");

    @Setup
    public void setup() {
        String[] segments = new String[depth];
        segments[depth - 1] = "v";
        Object value = Map.of("v", scalar);
        for (int i = depth - 2; i >= 0; i--) {
            boolean array = shape.equals("mixed") && i % 2 == 0;
            segments[i] = array ? "0" : "k" + i;
            value = array ? List.of(value) : Map.of(segments[i], value);
        }
        pointer = new JsonPointer(List.of(segments));
        parentPointer = new JsonPointer(pointer.segments().subList(0, depth - 1));
        tree = Json.builder().provider(engine).build().toTree(value);
        parent = tree.at(parentPointer);
        if (!tree.at(pointer).toJavaValue().equals(scalar)) {
            throw new AssertionError("Unexpected traversal result");
        }
        pointerWrite();
        if (!tree.at(pointer).toJavaValue().equals(scalar)) {
            throw new AssertionError("Unexpected write result");
        }
    }

    @Benchmark
    public JsonValue chainView() {
        JsonValue value = tree;
        for (String segment : pointer.segments()) {
            value = value instanceof JsonArray ? value.node(0) : value.node(segment);
        }
        return value;
    }

    @Benchmark
    public JsonValue pointerView() {
        return tree.at(pointer);
    }

    @Benchmark
    public Object pointerScalar() {
        return tree.at(pointer).toJavaValue();
    }

    @Benchmark
    public int pointerInt() {
        return tree.at(pointer).asInt();
    }

    @Benchmark
    public void pointerWrite() {
        tree.at(parentPointer).asObject().put("v", scalar);
    }

    @Benchmark
    public void cachedWrite() {
        parent.asObject().put("v", scalar);
    }

    @Benchmark
    public void containerWrite() {
        tree.at(parentPointer).asObject().put("payload", payload);
    }
}
