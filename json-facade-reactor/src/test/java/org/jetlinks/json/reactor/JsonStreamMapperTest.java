package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.jetlinks.json.spi.JsonProvider;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.reactivestreams.Subscription;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.BaseSubscriber;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStreamMapperTest {
    @Test
    void ambiguousDefaultDiscoveryCanBeConfiguredAndNamedMappersKeepIndependentPolicies() {
        JsonException failure = assertThrows(JsonException.class, Json::global);
        assertEquals(JsonException.Kind.PROVIDER, failure.kind());
        JsonOptions d = JsonOptions.DEFAULT;
        JsonOptions strict = new JsonOptions(true, true, true, false, true,
                d.maxDepth(), d.maxStringLength(), d.maxNumberLength(), d.maxValueBytes());
        JsonMapper configured = Json.builder().provider("jackson2").options(strict).build();
        JsonMapper named = Json.builder().provider("jackson3").build();
        Json.registerGlobal(configured);
        Json.registerGlobal("stream-jackson3", named);
        assertSame(configured, Json.global());
        assertSame(configured, Json.defaultMapper());
        assertSame(named, Json.global("stream-jackson3"));
        assertThrows(JsonException.class, () -> Json.global().read("{\"a\":1,\"a\":2}", Map.class));
        assertEquals(Map.of("a", 2), Json.global("stream-jackson3").read("{\"a\":1,\"a\":2}", Map.class));
        JsonStreamMapper streams = JsonStreamMapper.of(Json.global());
        StepVerifier.create(streams.readBytes(Flux.just(bytes("[1,2]")), JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))).expectNext(1, 2).verifyComplete();
        assertEquals("[{\"value\":1}]", join(streams.writeBytes(Flux.just(Map.of("value", 1)),
                JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY))));
        assertTrue(Json.global().write(Map.of("value", 1)).contains("\n"));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void recordIdentityScopeDoesNotRetainPreviouslyWrittenObjects(JsonProvider provider) {
        JsonMapper mapper = Json.builder().provider(provider).build();
        Identified value = new Identified();
        String json = join(JsonStreamMapper.of(mapper).writeBytes(Flux.just(value, value),
                JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)));
        assertEquals(List.of(Map.of("@id", 1, "name", "item"), Map.of("@id", 1, "name", "item")),
                mapper.read(json, List.class));
    }

    @com.fasterxml.jackson.annotation.JsonIdentityInfo(
            generator = com.fasterxml.jackson.annotation.ObjectIdGenerators.IntSequenceGenerator.class)
    static final class Identified {
        public String name = "item";
    }

    static Stream<JsonProvider> providers() {
        return Stream.of(new Jackson2Provider(), new Jackson3Provider());
    }

    private static JsonStreamMapper stream(JsonProvider provider) {
        return JsonStreamMapper.of(Json.builder().provider(provider).build());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void decodingRespectsDemandCancelsSourceAndPropagatesContext(JsonProvider provider) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger requested = new AtomicInteger();
        Flux<byte[]> input = Flux.deferContextual(context -> {
            assertEquals("trace", context.get("request"));
            return Flux.just(bytes("[1,2,"), bytes("3,4]")).doOnRequest(n -> requested.incrementAndGet())
                    .doOnCancel(() -> cancelled.set(true));
        });
        StepVerifier.create(stream(provider).readBytes(input, JsonType.of(Integer.class),
                        JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).contextWrite(c -> c.put("request", "trace")), 0)
                .then(() -> assertEquals(0, requested.get()))
                .thenRequest(1).expectNext(1)
                .thenRequest(1).expectNext(2)
                .thenCancel().verify();
        assertTrue(cancelled.get());
        assertEquals(1, requested.get());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationDoesNotWaitForAnActiveNativeBindingCallback(JsonProvider provider) throws Exception {
        GatedModule module = new GatedModule();
        JsonMapper mapper = Json.builder().provider(provider).module(module).build();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        BaseSubscriber<GatedItem> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // Start demand on a separate thread so cancellation can race with real binding.
            }

            @Override
            protected void hookOnNext(GatedItem item) {
                delivered.incrementAndGet();
            }

            @Override
            protected void hookOnError(Throwable failure) {
                error.set(failure);
            }
        };
        Flux<byte[]> input = Flux.just(bytes("[1,2]")).doOnCancel(() -> cancelled.set(true));
        JsonStreamMapper.of(mapper).readBytes(input, JsonType.of(GatedItem.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).subscribe(subscriber);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> request = executor.submit(() -> subscriber.request(1));
            assertTrue(module.entered.await(5, TimeUnit.SECONDS));
            executor.submit(subscriber::cancel).get(2, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
            assertEquals(1, module.release.getCount());
            module.release.countDown();
            request.get(5, TimeUnit.SECONDS);
            assertEquals(0, delivered.get());
            assertNull(error.get());
        } finally {
            module.release.countDown();
            subscriber.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void nullPolicyAndUpstreamErrorsAreExplicit(JsonProvider provider) {
        JsonStreamMapper mapper = stream(provider);
        JsonReadOptions array = JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS);
        StepVerifier.create(mapper.readBytes(Flux.just(bytes("[null,1]")), JsonType.of(Object.class), array))
                .expectError(JsonException.class).verify();
        JsonReadOptions skip = new JsonReadOptions(array.mode(), null, JsonReadOptions.NullPolicy.SKIP, 1000);
        StepVerifier.create(mapper.readBytes(Flux.just(bytes("[null,null,1]")), JsonType.of(Integer.class), skip))
                .expectNext(1).verifyComplete();
        StepVerifier.create(mapper.readBytes(Flux.just(bytes("[null]")), JsonType.of(JsonValue.class), array))
                .expectNext(JsonValue.NULL).verifyComplete();
        RuntimeException failure = new RuntimeException("source");
        StepVerifier.create(mapper.readBytes(Flux.error(failure), JsonType.of(Object.class), array))
                .expectErrorMatches(error -> error == failure).verify();
        StepVerifier.create(mapper.readBytes(Flux.just(bytes("[1")), JsonType.of(Integer.class), array))
                .expectNext(1).expectError(JsonException.class).verify();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationDoesNotWaitForAnActiveNativeWriter(JsonProvider provider) throws Exception {
        GatedModule module = new GatedModule(true);
        JsonMapper mapper = Json.builder().provider(provider).module(module).build();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        BaseSubscriber<byte[]> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // Demand is deliberately started on the operation thread below.
            }

            @Override
            protected void hookOnNext(byte[] bytes) {
                delivered.incrementAndGet();
            }

            @Override
            protected void hookOnError(Throwable failure) {
                error.set(failure);
            }
        };
        JsonStreamMapper.of(mapper).writeBytes(Flux.just(new GatedItem(1))
                        .doOnCancel(() -> cancelled.set(true)),
                JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON)).subscribe(subscriber);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> request = executor.submit(() -> subscriber.request(1));
            assertTrue(module.entered.await(5, TimeUnit.SECONDS));
            executor.submit(subscriber::cancel).get(2, TimeUnit.SECONDS);
            assertTrue(cancelled.get());
            assertEquals(1, module.release.getCount());
            module.release.countDown();
            request.get(5, TimeUnit.SECONDS);
            assertEquals(0, delivered.get());
            assertNull(error.get());
        } finally {
            module.release.countDown();
            subscriber.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void bufferCopyAllowsSourceReuseAndPreservesEveryCursorKind(JsonProvider provider) {
        for (int kind = 0; kind < 4; kind++) {
            ByteBuffer source = kind == 0 ? ByteBuffer.allocateDirect(20) : ByteBuffer.allocate(20);
            source.put((byte) 'x').put(bytes("[1,2]"));
            source.position(1).limit(6);
            if (kind == 2) {
                source = source.asReadOnlyBuffer();
            } else if (kind == 3) {
                source = source.slice();
            }
            source.mark();
            int position = source.position();
            int limit = source.limit();
            ByteBuffer input = source;
            Flux<ByteBuffer> chunks = Flux.create(sink -> {
                sink.next(input);
                // The adapter must already have copied when this callback regains control.
                if (!input.isReadOnly()) {
                    for (int i = input.position(); i < input.limit(); i++) {
                        input.put(i, (byte) '!');
                    }
                }
                sink.complete();
            });
            StepVerifier.create(stream(provider).readBuffers(chunks, JsonType.of(Integer.class),
                            JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)), 1)
                    .expectNext(1).thenRequest(2).expectNext(2).verifyComplete();
            assertEquals(position, input.position());
            assertEquals(limit, input.limit());
            input.reset();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void outputFormatsEmptySourcesAndReadOnlyBuffersHaveStableOwnership(JsonProvider provider) {
        JsonStreamMapper mapper = stream(provider);
        for (JsonWriteOptions.Format format : JsonWriteOptions.Format.values()) {
            if (format == JsonWriteOptions.Format.SINGLE_VALUE) {
                continue;
            }
            JsonWriteOptions options = new JsonWriteOptions(format, 2, 1000);
            String expected = format == JsonWriteOptions.Format.ARRAY ? "[1,2]" : "1\n2\n";
            assertEquals(expected, join(mapper.writeBytes(Flux.just(1, 2), options)));
            assertEquals(format == JsonWriteOptions.Format.ARRAY ? "[]" : "",
                    join(mapper.writeBytes(Flux.empty(), options)));
            List<ByteBuffer> retained = new ArrayList<>();
            StepVerifier.create(mapper.writeBuffers(Flux.just(1, 2), options).doOnNext(buffer -> {
                assertTrue(buffer.isReadOnly());
                assertEquals(0, buffer.position());
                retained.add(buffer);
            })).thenConsumeWhile(buffer -> true).verifyComplete();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (ByteBuffer buffer : retained) {
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            assertEquals(expected, output.toString(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void errorsDoNotAppendClosingArrayAndCancellationStopsEncoding(JsonProvider provider) {
        JsonStreamMapper mapper = stream(provider);
        RuntimeException source = new RuntimeException("source");
        List<byte[]> emitted = new ArrayList<>();
        StepVerifier.create(mapper.writeBytes(Flux.concat(Flux.just(1), Flux.error(source)),
                        JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)).doOnNext(emitted::add))
                .thenConsumeWhile(chunk -> true).expectErrorMatches(error -> error == source).verify();
        assertEquals("[1", join(Flux.fromIterable(emitted)));
        AtomicInteger consumed = new AtomicInteger();
        Flux<byte[]> output = mapper.writeBytes(Flux.range(0, 100).doOnNext(v -> consumed.incrementAndGet()),
                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 1, 1000));
        StepVerifier.create(output, 1).expectNextMatches(chunk -> chunk[0] == '[').thenCancel().verify();
        assertEquals(0, consumed.get());
        StepVerifier.create(mapper.writeBytes(Flux.just("too long"),
                        new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 1, 2)))
                .expectError(JsonException.class).verify();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void prettyMapperProducesValidCompactNdjsonWithoutChangingStrings(JsonProvider provider) {
        JsonOptions options = new JsonOptions(true, false, false, false, true, 256, 10000, 1000, 10000);
        JsonMapper mapper = Json.builder().provider(provider).options(options).build();
        assertTrue(mapper.write(Map.of("x", 1)).contains("\n"));
        String record = "{\"s\":\"a \\t\\\\\\\"b\"}";
        Map<?, ?> value = mapper.read(record, Map.class);
        String output = join(JsonStreamMapper.of(mapper).writeBytes(Flux.just(value),
                JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON)));
        assertEquals(record + "\n", output);
        StepVerifier.create(JsonStreamMapper.of(mapper).readBytes(Flux.just(bytes(output)),
                        JsonType.of(Object.class), JsonReadOptions.of(JsonReadOptions.Mode.NDJSON)))
                .expectNext(value).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void subscriptionsHaveIndependentParserAndFormattingState(JsonProvider provider) {
        JsonStreamMapper mapper = stream(provider);
        Flux<Integer> records = mapper.readBytes(Flux.just(bytes("[1,2]")), JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
        StepVerifier.create(records).expectNext(1, 2).verifyComplete();
        StepVerifier.create(records).expectNext(1, 2).verifyComplete();
        Flux<byte[]> output = mapper.writeBytes(Flux.just(1, 2), JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY));
        assertEquals("[1,2]", join(output));
        assertEquals("[1,2]", join(output));
        StepVerifier.create(mapper.readBytes(Flux.just(new byte[0], bytes("[]"), new byte[0]),
                JsonType.of(Object.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))).verifyComplete();
    }

    @Test
    void discoveryFailsForAmbiguousClasspathAndNullArgumentsFailEarly() {
        assertThrows(JsonException.class, () -> Json.builder().build());
        assertThrows(NullPointerException.class, () -> JsonStreamMapper.of(null));
    }

    private static String join(Flux<byte[]> output) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StepVerifier.create(output.doOnNext(bytes::writeBytes)).thenConsumeWhile(chunk -> true).verifyComplete();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String input) {
        return input.getBytes(StandardCharsets.UTF_8);
    }

    /** Callback gating makes the cancellation ordering deterministic without sleeps. */
    private static final class GatedModule implements JsonModule {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final boolean writing;

        private GatedModule() {
            this(false);
        }

        private GatedModule(boolean writing) {
            this.writing = writing;
        }

        @Override
        public String id() {
            return "gated";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(GatedItem.class, new GatedAdapter(entered, release, writing));
        }
    }

    private static final class GatedAdapter implements JsonAdapter<GatedItem> {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final boolean writing;

        private GatedAdapter(CountDownLatch entered, CountDownLatch release, boolean writing) {
            this.entered = entered;
            this.release = release;
            this.writing = writing;
        }

        @Override
        public void write(GatedItem item, JsonWriter writer) {
            if (writing) {
                awaitRelease();
            }
            writer.value(item.value());
        }

        @Override
        public GatedItem read(JsonReader reader) {
            awaitRelease();
            return new GatedItem(reader.readValue(JsonType.of(Integer.class)));
        }

        private void awaitRelease() {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private record GatedItem(int value) { }
}
