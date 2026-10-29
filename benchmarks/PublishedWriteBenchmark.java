package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonWriteOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

/** 比较独立持有的输出；原生参考不包含需求、取消和门面预算管理。 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PublishedWriteBenchmark {
    private static volatile byte[] publishedLast;
    @Param({"jackson2", "jackson3"})
    public String engine;
    @Param({"20", "4096"})
    public int textSize;
    @Param({"ascii"})
    public String textKind;
    @Param({"0"})
    public int recordCount;
    private JsonStreamMapper streams;
    private com.fasterxml.jackson.databind.ObjectWriter writer2;
    private tools.jackson.databind.ObjectWriter writer3;
    private Map<String, Object> value;
    private int records;
    private long expectedLength;
    private final PublishingOutput output = new PublishingOutput();
    private final JsonWriteOptions options = new JsonWriteOptions(JsonWriteOptions.Format.ARRAY,
            8192, 8 * 1024 * 1024);
    private List<byte[]> retained;

    @Setup
    public void setup() throws Exception {
        JsonMapper facade;
        if (engine.equals("jackson2")) {
            facade = Json.builder().provider(engine).configureNative(
                    com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> writer2 = builder.build().writer()
                            .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT,
                                    com.fasterxml.jackson.databind.SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
                            .withRootValueSeparator("")).build();
        } else {
            facade = Json.builder().provider(engine).configureNative(
                    tools.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> writer3 = builder.build().writer()
                            .without(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT,
                                    tools.jackson.databind.SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
                            .withRootValueSeparator("")).build();
        }
        streams = JsonStreamMapper.of(facade);
        String unit = switch (textKind) {
            case "ascii" -> "x";
            case "unicode" -> "中😀";
            case "escaped" -> "\"\n\\";
            default -> throw new IllegalArgumentException(textKind);
        };
        String text = unit.repeat(Math.max(1, textSize / unit.length()));
        value = Map.of("n", 1, "s", text);
        records = recordCount > 0 ? recordCount : textSize == 20 ? 1000 : 256;
        byte[] expected = facade.writeBytes(Collections.nCopies(records, value));
        expectedLength = expected.length;
        retained = new ArrayList<>();
        if (nativeOwnedLowerBound() != expectedLength || output.closed) {
            throw new AssertionError("Native output count/ownership");
        }
        List<byte[]> nativeChunks = retained;
        retained = new ArrayList<>();
        nativeGeneratorOwned();
        List<byte[]> generatorChunks = retained;
        retained = new ArrayList<>();
        facadeOwned();
        List<byte[]> facadeChunks = retained;
        retained = null;
        Map<String, Object> original = value;
        // 改变内容但保留 UTF-8 与转义后的长度，便于复用同一输出计数校验。
        value = Map.of("n", 2, "s", text.replace('x', 'y').replace('中', '文').replace('\n', '\t'));
        nativeOwnedLowerBound();
        facadeOwned();
        value = original;
        if (!Arrays.equals(expected, join(nativeChunks)) || !Arrays.equals(expected, join(generatorChunks))
                || !Arrays.equals(expected, join(facadeChunks))) {
            throw new AssertionError("Published bytes changed or differ from expected document");
        }
    }

    @Benchmark
    public long nativeOwnedLowerBound() throws Exception {
        output.count = 0;
        if (writer2 != null) {
            try (var generator = writer2.createGenerator(output)) {
                generator.writeStartArray();
                generator.flush();
                for (int i = 0; i < records; i++) {
                    writer2.writeValue(generator, value);
                    generator.flush();
                }
                generator.writeEndArray();
                generator.flush();
            }
        } else {
            try (var generator = writer3.createGenerator(output)) {
                generator.writeStartArray();
                generator.flush();
                for (int i = 0; i < records; i++) {
                    writer3.writeValue(generator, value);
                    generator.flush();
                }
                generator.writeEndArray();
                generator.flush();
            }
        }
        return output.count;
    }

    /** 直接调用原生 generator，区分绑定上下文成本；保持输出所有权和刷新边界一致。 */
    @Benchmark
    public long nativeGeneratorOwned() throws Exception {
        output.count = 0;
        if (writer2 != null) {
            try (var generator = writer2.createGenerator(output)) {
                generator.writeStartArray();
                generator.flush();
                for (int i = 0; i < records; i++) {
                    generator.writeStartObject();
                    for (var entry : value.entrySet()) {
                        generator.writeFieldName(entry.getKey());
                        if (entry.getValue() instanceof String text) generator.writeString(text);
                        else generator.writeNumber(((Number) entry.getValue()).intValue());
                    }
                    generator.writeEndObject();
                    generator.flush();
                }
                generator.writeEndArray();
                generator.flush();
            }
        } else {
            try (var generator = writer3.createGenerator(output)) {
                generator.writeStartArray();
                generator.flush();
                for (int i = 0; i < records; i++) {
                    generator.writeStartObject();
                    for (var entry : value.entrySet()) {
                        generator.writeName(entry.getKey());
                        if (entry.getValue() instanceof String text) generator.writeString(text);
                        else generator.writeNumber(((Number) entry.getValue()).intValue());
                    }
                    generator.writeEndObject();
                    generator.flush();
                }
                generator.writeEndArray();
                generator.flush();
            }
        }
        if (output.count != expectedLength) throw new AssertionError("Native generator byte count");
        return output.count;
    }

    @Benchmark
    public long facadeOwned() {
        Counter counter = new Counter();
        streams.writeBytes(Flux.range(0, records).map(i -> value), options).subscribe(counter);
        if (!counter.complete || counter.error != null || counter.sum != expectedLength) {
            throw new AssertionError("Output count/completion", counter.error);
        }
        return counter.sum;
    }

    private void publish(byte[] bytes) {
        publishedLast = bytes;
        if (retained != null) {
            retained.add(bytes);
        }
    }

    private static byte[] join(List<byte[]> chunks) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        chunks.forEach(result::writeBytes);
        return result.toByteArray();
    }

    private final class PublishingOutput extends OutputStream {
        private long count;
        private boolean closed;

        @Override
        public void write(int value) {
            publish(new byte[]{(byte) value});
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            publish(Arrays.copyOfRange(bytes, offset, offset + length));
            count += length;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final class Counter implements Subscriber<byte[]> {
        private long sum;
        private Throwable error;
        private boolean complete;

        @Override
        public void onSubscribe(Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(byte[] bytes) {
            publish(bytes);
            sum += bytes.length;
        }

        @Override
        public void onError(Throwable failure) {
            error = failure;
        }

        @Override
        public void onComplete() {
            complete = true;
        }
    }
}
