package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.JsonWriteOptions;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/** 分别测解析、Session 与 Reactor 交付；写出采用独立数组和逐事件 flush。
 * 原生及同步游标参考不具备 Reactor 的预算、背压和取消能力，属于功能较少的下界。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class EventPipelineBenchmark {
    private static volatile byte[] publishedLast;
    @Param({"jackson2", "jackson3"}) public String engine;
    @Param({"1000"}) public int records;
    @Param({"20"}) public int textSize;
    private JsonMapper mapper;
    private JsonStreamMapper streams;
    private com.fasterxml.jackson.databind.ObjectReader reader2;
    private tools.jackson.databind.ObjectReader reader3;
    private byte[][] chunks;
    private List<JsonEvent> events;
    private long expectedChecksum;
    private long expectedBytes;
    private long checksum;
    private final PublishingOutput output = new PublishingOutput();
    private final JsonWriteOptions options = JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE);

    @Setup
    public void setup() throws Exception {
        if (engine.equals("jackson2")) {
            mapper = Json.builder().provider(engine).configureNative(
                    com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> reader2 = builder.build().reader()).build();
        } else {
            mapper = Json.builder().provider(engine).configureNative(
                    tools.jackson.databind.json.JsonMapper.Builder.class,
                    builder -> reader3 = builder.build().reader()).build();
        }
        streams = JsonStreamMapper.of(mapper);
        String text = "x".repeat(textSize);
        byte[] document = mapper.writeBytes(Collections.nCopies(records,
                Map.of("id", 123, "text", text, "active", true)));
        expectedBytes = document.length;
        chunks = new byte[(document.length + 8191) / 8192][];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = Arrays.copyOfRange(document, i * 8192, Math.min(document.length, (i + 1) * 8192));
        }
        List<JsonEvent> row = List.of(JsonEvent.START_OBJECT, JsonEvent.name("id"), JsonEvent.number("123"),
                JsonEvent.name("text"), JsonEvent.string(text), JsonEvent.name("active"), JsonEvent.TRUE,
                JsonEvent.END_OBJECT);
        events = new ArrayList<>();
        events.add(JsonEvent.START_ARRAY);
        for (int i = 0; i < records; i++) events.addAll(row);
        events.add(JsonEvent.END_ARRAY);
        expectedChecksum = records * (27L + textSize) + 2;
        nativeRead();
        sessionRead();
        reactorRead();
        cursorWrite();
        reactorWrite();
        documentCopy();
        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        Counter<byte[]> validation = new Counter<>() {
            @Override long measureValue(byte[] value) { assembled.writeBytes(value); return value.length; }
        };
        streams.writeEvents(Flux.fromIterable(events), options).subscribe(validation);
        validation.verify(expectedBytes);
        if (!mapper.read(document, Object.class).equals(mapper.read(assembled.toByteArray(), Object.class))) {
            throw new AssertionError("Event output content mismatch");
        }
    }

    @Benchmark
    public long nativeRead() throws Exception {
        checksum = 0;
        if (reader2 != null) {
            try (var parser = reader2.getFactory().createNonBlockingByteBufferParser()) {
                reader2.getConfig().initialize(parser);
                var feeder = (com.fasterxml.jackson.core.async.ByteBufferFeeder) parser.getNonBlockingInputFeeder();
                for (byte[] chunk : chunks) {
                    feeder.feedInput(ByteBuffer.wrap(chunk));
                    drain2(parser);
                }
                feeder.endOfInput();
                drain2(parser);
            }
        } else {
            try (var parser = reader3.createNonBlockingByteBufferParser()) {
                var feeder = (tools.jackson.core.async.ByteBufferFeeder) parser.nonBlockingInputFeeder();
                for (byte[] chunk : chunks) {
                    feeder.feedInput(ByteBuffer.wrap(chunk));
                    drain3(parser);
                }
                feeder.endOfInput();
                drain3(parser);
            }
        }
        return verifyChecksum();
    }

    private void drain2(com.fasterxml.jackson.core.JsonParser parser) throws Exception {
        com.fasterxml.jackson.core.JsonToken token;
        while ((token = parser.nextToken()) != null && token != com.fasterxml.jackson.core.JsonToken.NOT_AVAILABLE) {
            checksum += 1 + (token.isScalarValue() && token != com.fasterxml.jackson.core.JsonToken.VALUE_NULL
                    || token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME ? parser.getText().length() : 0);
        }
    }

    private void drain3(tools.jackson.core.JsonParser parser) {
        tools.jackson.core.JsonToken token;
        while ((token = parser.nextToken()) != null && token != tools.jackson.core.JsonToken.NOT_AVAILABLE) {
            checksum += 1 + (token.isScalarValue() && token != tools.jackson.core.JsonToken.VALUE_NULL
                    || token == tools.jackson.core.JsonToken.PROPERTY_NAME ? parser.getString().length() : 0);
        }
    }

    @Benchmark
    public long sessionRead() {
        checksum = 0;
        JsonReadSession.Receiver<JsonEvent, JsonReadSession.State> receiver = (state, event) -> {
            if (state == JsonReadSession.State.VALUE) checksum += measure(event);
            return state;
        };
        try (var session = mapper.eventSession()) {
            for (byte[] chunk : chunks) {
                session.feed(ByteBuffer.wrap(chunk));
                while (session.read(receiver, () -> false) == JsonReadSession.State.VALUE) { }
            }
            session.endInput();
            JsonReadSession.State state;
            do {
                state = session.read(receiver, () -> false);
            } while (state == JsonReadSession.State.VALUE);
            if (state != JsonReadSession.State.END) throw new AssertionError("Unexpected EOF");
        }
        return verifyChecksum();
    }

    @Benchmark
    public long reactorRead() {
        Counter<JsonEvent> counter = new Counter<>() {
            @Override long measureValue(JsonEvent event) { return measure(event); }
        };
        streams.readEvents(Flux.fromArray(chunks)).subscribe(counter);
        return counter.verify(expectedChecksum);
    }

    @Benchmark
    public long cursorWrite() {
        output.bytes = 0;
        try (JsonWriter writer = mapper.compactWriter(output)) {
            for (JsonEvent event : events) {
                JsonEvent.Token token = (JsonEvent.Token) event;
                switch (token.token()) {
                    case START_ARRAY -> writer.beginArray();
                    case END_ARRAY -> writer.endArray();
                    case START_OBJECT -> writer.beginObject();
                    case END_OBJECT -> writer.endObject();
                    case FIELD_NAME -> writer.name(token.text());
                    case STRING -> writer.string(token.text());
                    case NUMBER -> writer.number(token.text());
                    case BOOLEAN -> writer.bool(Boolean.parseBoolean(token.text()));
                    case NULL -> writer.nullValue();
                }
                writer.flush();
            }
        }
        if (output.bytes != expectedBytes) throw new AssertionError("Cursor byte count");
        return output.bytes;
    }

    @Benchmark public long reactorWrite() {
        return consume(streams.writeEvents(Flux.fromIterable(events), options));
    }

    @Benchmark public long documentCopy() {
        return consume(streams.writeEvents(streams.readEvents(Flux.fromArray(chunks)), options));
    }

    private long consume(Flux<byte[]> bytes) {
        Counter<byte[]> counter = new Counter<>() {
            @Override long measureValue(byte[] value) { publishedLast = value; return value.length; }
        };
        bytes.subscribe(counter);
        return counter.verify(expectedBytes);
    }

    private long verifyChecksum() {
        if (checksum != expectedChecksum) throw new AssertionError("Event checksum: " + checksum);
        return checksum;
    }

    private static long measure(JsonEvent event) {
        String text = ((JsonEvent.Token) event).text();
        return 1L + (text == null ? 0 : text.length());
    }

    private static final class PublishingOutput extends OutputStream {
        long bytes;
        @Override public void write(int value) { publishedLast = new byte[]{(byte) value}; bytes++; }
        @Override public void write(byte[] value, int offset, int length) {
            publishedLast = Arrays.copyOfRange(value, offset, offset + length);
            bytes += length;
        }
    }

    private abstract static class Counter<T> extends BaseSubscriber<T> {
        long sum;
        boolean complete;
        Throwable failure;
        abstract long measureValue(T value);
        @Override protected void hookOnNext(T value) { sum += measureValue(value); }
        @Override protected void hookOnComplete() { complete = true; }
        @Override protected void hookOnError(Throwable error) { failure = error; }
        long verify(long expected) {
            if (!complete || failure != null || sum != expected) throw new AssertionError("Result mismatch", failure);
            return sum;
        }
    }
}
