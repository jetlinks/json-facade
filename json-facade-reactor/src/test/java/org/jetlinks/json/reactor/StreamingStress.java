package org.jetlinks.json.reactor;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

/** Standalone bounded-heap acceptance harness. Never accumulates the logical document.
 * Run with scripts/run-streaming-stress.py after test-compile.
 */
public final class StreamingStress {
    private static final String TEXT = "x".repeat(1000);
    private static final Map<String, Object> VALUE = Map.of("n", 1, "s", TEXT, "a", List.of(true, false));

    private StreamingStress() {
    }

    public static void main(String[] args) throws Exception {
        String engine = args[0];
        String mode = args[1];
        long targetBytes = Long.parseLong(args[2]) * 1024 * 1024;
        int chunkSize = Integer.parseInt(args[3]);
        JsonMapper mapper = Json.builder().provider(engine.equals("2")
                ? new Jackson2Provider() : new Jackson3Provider()).build();
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        byte[] row = mapper.writeBytes(VALUE);
        long records = (targetBytes + row.length) / (row.length + 1);
        records = Math.max(1, records);
        String framing = mode.startsWith("ndjson") ? "ndjson" : mode.startsWith("array") ? "array" : "wrapped";
        GeneratedInput generated = new GeneratedInput(row, records, chunkSize, framing);
        Flux<byte[]> input = Flux.generate(sink -> {
            byte[] chunk = generated.next();
            if (chunk == null) {
                sink.complete();
            } else {
                sink.next(chunk);
            }
        });
        long allocated = allocated();
        long started = System.nanoTime();
        Check<?> check;
        if (mode.endsWith("read")) {
            JsonReadOptions options = framing.equals("wrapped")
                    ? new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                    new JsonReadOptions.Selection(JsonPointer.of("/items"), true), JsonReadOptions.NullPolicy.FAIL, 8192)
                    : JsonReadOptions.of(framing.equals("array")
                    ? JsonReadOptions.Mode.ARRAY_ELEMENTS : JsonReadOptions.Mode.NDJSON);
            Records result = new Records(records);
            streams.readBytes(input, new JsonType<Map<String, Object>>() { }, options).subscribe(result);
            check = result;
        } else {
            NativeCheck result = new NativeCheck(records, chunkSize, framing);
            if (mode.equals("document-copy")) {
                streams.writeEvents(streams.readEvents(input),
                        new JsonWriteOptions(JsonWriteOptions.Format.SINGLE_VALUE, chunkSize, 8192)).subscribe(result);
            } else {
                Flux<Map<String, Object>> values = Flux.generate(() -> 0L, (index, sink) -> {
                    if (index == result.expected) {
                        sink.complete();
                    } else {
                        sink.next(VALUE);
                    }
                    return index + 1;
                });
                streams.writeBytes(values, new JsonWriteOptions(framing.equals("array")
                        ? JsonWriteOptions.Format.ARRAY : JsonWriteOptions.Format.NDJSON, chunkSize, 8192))
                        .subscribe(result);
            }
            check = result;
        }
        check.verify();
        long bytes = mode.endsWith("read") ? generated.bytes : ((NativeCheck) check).bytes;
        require(bytes >= targetBytes, "Logical size below requested target");
        long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        long gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
        System.out.printf("{\"engine\":\"%s\",\"mode\":\"%s\",\"chunk\":%d,\"bytes\":%d,"
                        + "\"records\":%d,\"millis\":%.3f,\"allocatedBytes\":%d,\"heapPoolPeakBytes\":%d,"
                        + "\"heapUsedBytes\":%d,\"gcMillis\":%d,\"firstSignalMicros\":%.3f,\"maxHeap\":%d}%n",
                engine, mode, chunkSize, bytes, records, (System.nanoTime() - started) / 1_000_000.0,
                allocated() - allocated, peak, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                gc, (check.first - started) / 1000.0, Runtime.getRuntime().maxMemory());
    }

    private static long allocated() {
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private abstract static class Check<T> implements Subscriber<T> {
        final long expected;
        private Subscription subscription;
        private Throwable failure;
        private boolean done;
        long first;

        Check(long expected) {
            this.expected = expected;
        }

        @Override
        public final void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public final void onNext(T item) {
            try {
                if (first == 0) {
                    first = System.nanoTime();
                }
                accept(item);
                subscription.request(1);
            } catch (Exception | AssertionError error) {
                subscription.cancel();
                failure = error;
            }
        }

        abstract void accept(T item) throws Exception;
        abstract void finish() throws Exception;

        @Override
        public final void onError(Throwable error) {
            failure = error;
        }

        @Override
        public final void onComplete() {
            try {
                finish();
                done = true;
            } catch (Exception | AssertionError error) {
                failure = error;
            }
        }

        final void verify() {
            if (failure != null) {
                throw new AssertionError("Streaming stress failed", failure);
            }
            require(done, "Synchronous pipeline did not complete");
        }
    }

    private static final class Records extends Check<Map<String, Object>> {
        private long count;

        Records(long expected) {
            super(expected);
        }

        @Override
        void accept(Map<String, Object> value) {
            require(VALUE.equals(value), "Incorrect bound row");
            count++;
        }

        @Override
        void finish() {
            require(count == expected, "Incorrect bound record count");
        }
    }

    /** Independent native validation detects incomplete or malformed facade output. */
    private static final class NativeCheck extends Check<byte[]> {
        private final JsonParser parser = new JsonFactory().createNonBlockingByteArrayParser();
        private final ByteArrayFeeder feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        private final int chunkSize;
        private final boolean wrapped;
        private final boolean array;
        private long count;
        private long strings;
        private long summaries;
        private long booleans;
        private long roots;
        private int depth;
        private long bytes;

        NativeCheck(long expected, int chunkSize, String framing) throws Exception {
            super(expected);
            this.chunkSize = chunkSize;
            wrapped = framing.equals("wrapped");
            array = framing.equals("array");
        }

        @Override
        void accept(byte[] chunk) throws Exception {
            require(chunk.length > 0 && chunk.length <= chunkSize, "Invalid output chunk size");
            require(feeder.needMoreInput(), "Verifier retained an input chunk");
            bytes += chunk.length;
            feeder.feedInput(chunk, 0, chunk.length);
            drain();
        }

        private void drain() throws Exception {
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                observeStructure(token);
                if (token == JsonToken.VALUE_NUMBER_INT && "n".equals(parser.currentName())) {
                    require(depth == (wrapped ? 3 : array ? 2 : 1), "Incorrect row location");
                    require(parser.getIntValue() == 1, "Incorrect numeric value");
                    count++;
                } else if (token == JsonToken.VALUE_STRING && "s".equals(parser.currentName())) {
                    require(TEXT.equals(parser.getText()), "Incorrect string value");
                    strings++;
                } else if (token == JsonToken.VALUE_NUMBER_INT && "count".equals(parser.currentName())) {
                    require(parser.getLongValue() == expected, "Incorrect trailing summary");
                    summaries++;
                } else if (token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE) {
                    require(token == (booleans % 2 == 0 ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE),
                            "Incorrect nested array contents");
                    booleans++;
                }
            }
        }

        private void observeStructure(JsonToken token) {
            if (token.isStructStart()) {
                if (depth == 0) {
                    roots++;
                    require(token == (array ? JsonToken.START_ARRAY : JsonToken.START_OBJECT),
                            "Incorrect root shape");
                }
                depth++;
            } else if (token.isStructEnd()) {
                depth--;
            }
        }

        @Override
        void finish() throws Exception {
            feeder.endOfInput();
            drain();
            parser.close();
            require(count == expected && strings == expected, "Incorrect output count/content");
            require(summaries == (wrapped ? 1 : 0), "Missing trailing metadata");
            require(roots == (wrapped || array ? 1 : expected), "Incorrect root count");
            require(booleans == expected * 2 && depth == 0, "Incorrect nested shape");
        }
    }

    /** Produces bytes independently of the facade writer, with at most one source chunk retained. */
    private static final class GeneratedInput {
        private final byte[] row;
        private final long records;
        private final int chunkSize;
        private final byte[] prefix;
        private final byte[] suffix;
        private final byte[] separator;
        private final boolean ndjson;
        private byte[] part = new byte[0];
        private int offset;
        private int stage;
        private long emitted;
        private long bytes;

        GeneratedInput(byte[] row, long records, int chunkSize, String framing) {
            this.row = row;
            this.records = records;
            this.chunkSize = chunkSize;
            ndjson = framing.equals("ndjson");
            prefix = (ndjson ? "" : framing.equals("array") ? "[" : "{\"meta\":{\"version\":1},\"items\":[")
                    .getBytes(StandardCharsets.UTF_8);
            suffix = (ndjson ? "" : framing.equals("array") ? "]" : "],\"summary\":{\"count\":" + records + "}}")
                    .getBytes(StandardCharsets.UTF_8);
            separator = new byte[]{(byte) (ndjson ? '\n' : ',')};
        }

        byte[] next() {
            if (part == null) {
                return null;
            }
            byte[] chunk = new byte[chunkSize];
            int used = 0;
            while (used < chunk.length) {
                if (offset == part.length) {
                    part = nextPart();
                    offset = 0;
                    if (part == null) {
                        break;
                    }
                }
                int length = Math.min(chunk.length - used, part.length - offset);
                System.arraycopy(part, offset, chunk, used, length);
                used += length;
                offset += length;
            }
            bytes += used;
            return used == 0 ? null : used == chunk.length ? chunk : Arrays.copyOf(chunk, used);
        }

        private byte[] nextPart() {
            if (stage == 0) {
                stage = 1;
                return prefix;
            }
            if (stage == 2) {
                stage = 1;
                return ndjson || emitted < records ? separator : new byte[0];
            }
            if (stage == 1) {
                if (emitted < records) {
                    emitted++;
                    stage = 2;
                    return row;
                }
                stage = 3;
                return suffix;
            }
            return null;
        }
    }
}
