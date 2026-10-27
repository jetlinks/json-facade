package org.jetlinks.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;
import org.jetlinks.json.fastjson2.Fastjson2Provider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** 相同配置、完整根值检查及独立结果所有权；准备和复用成本分别测量。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Fastjson2Benchmark {
    @Param({"1", "1000"})
    public int rows;
    private JsonMapper facade;
    private JSONReader.Context reader;
    private JSONWriter.Context writer;
    private final JsonType<List<Map<String, Object>>> type = new JsonType<>() { };
    private List<Map<String, Object>> values;
    private byte[] bytes;

    @Setup
    public void setup() {
        facade = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions()).build();
        var readerProvider = new ObjectReaderProvider();
        readerProvider.setDisableAutoType(true);
        readerProvider.setAutoTypeBeforeHandler(null);
        reader = new JSONReader.Context(readerProvider, JSONReader.Feature.UseNativeObject.mask);
        reader.setMaxLevel(facade.options().maxDepth());
        writer = new JSONWriter.Context(new ObjectWriterProvider(), JSONWriter.Feature.WriteNulls);
        writer.setMaxLevel(facade.options().maxDepth());
        values = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i + 1000);
            row.put("name", "sensor-设备-" + i);
            row.put("active", true);
            values.add(row);
        }
        bytes = facade.writeBytes(values);
        if (!values.equals(nativeRead()) || !values.equals(facadeRead())
                || !values.equals(nativeConvert()) || !values.equals(facadeConvert())
                || !java.util.Arrays.equals(nativeWrite(), facadeWrite())) {
            throw new AssertionError("Native/facade workload mismatch");
        }
    }

    @Benchmark
    public Object facadeRead() {
        return facade.read(bytes, type);
    }

    @Benchmark
    public Object nativeRead() {
        try (JSONReader input = JSONReader.of(bytes, reader)) {
            return bind(input);
        }
    }

    @Benchmark
    public byte[] facadeWrite() {
        return facade.writeBytes(values);
    }

    @Benchmark
    public byte[] nativeWrite() {
        try (JSONWriter output = JSONWriter.ofUTF8(writer)) {
            output.setRootObject(values);
            output.writeAny(values);
            return output.getBytes();
        }
    }

    @Benchmark
    public Object facadeConvert() {
        return facade.convert(values, type);
    }

    @Benchmark
    public Object nativeConvert() {
        try (JSONWriter output = JSONWriter.ofUTF8(writer)) {
            output.setRootObject(values);
            output.writeAny(values);
            try (JSONReader input = JSONReader.of(output.getBytes(), reader)) {
                return bind(input);
            }
        }
    }

    @Benchmark
    public JsonMapper facadePrepare() {
        return facade.withOptions(JsonOperationOptions.builder().omitNulls(true).build());
    }

    private Object bind(JSONReader input) {
        Object result = input.read(type.type());
        input.handleResolveTasks(result);
        if (!input.isEnd()) {
            throw new IllegalStateException("Incomplete root validation");
        }
        return result;
    }
}
