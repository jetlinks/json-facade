package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredStreamingTest {
    private static final String PROVIDERS = "org.jetlinks.json.reactor.JsonStreamMapperTest#providers";

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void completeNestedDocumentsRoundTripThroughSingleByteInputAndDemand(JsonProvider provider) {
        JsonMapper mapper = Json.builder().provider(provider).build();
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        String document = "{\"meta\":{\"版本\":1},\"items\":[{\"n\":1.2300E+2,\"s\":\"中\\\"\\n\"},"
                + "[true,false,null],{}],\"summary\":{\"count\":3}}";
        Flux<JsonEvent> events = streams.readEvents(split(document));
        JsonWriteOptions options = new JsonWriteOptions(JsonWriteOptions.Format.SINGLE_VALUE, 7, 1024);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        AtomicBoolean complete = new AtomicBoolean();
        List<byte[]> retained = new ArrayList<>();
        Flux<byte[]> output = streams.writeEvents(events.doOnComplete(() -> complete.set(true)), options);
        StepVerifier.create(output.doOnNext(chunk -> {
            assertTrue(chunk.length > 0 && chunk.length <= 7);
            if (retained.isEmpty()) {
                assertFalse(complete.get());
            }
            retained.add(chunk);
            result.writeBytes(chunk);
        }), 0).thenRequest(1).expectNextMatches(bytes -> bytes[0] == '{')
                .thenRequest(Long.MAX_VALUE).thenConsumeWhile(bytes -> true).verifyComplete();
        assertEquals(document, result.toString(StandardCharsets.UTF_8));
        assertArrayEquals(result.toByteArray(), retained.stream()
                .collect(ByteArrayOutputStream::new, ByteArrayOutputStream::writeBytes,
                        (left, right) -> left.writeBytes(right.toByteArray())).toByteArray());
        StepVerifier.create(streams.readBufferEvents(split(document).map(ByteBuffer::wrap)))
                .thenConsumeWhile(event -> true).verifyComplete();
        assertEquals(document, join(output));
    }

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void tokenScalarsBypassBindingAdaptersWhileBoundValuesUseThem(JsonProvider provider) {
        JsonModule module = new JsonModule() {
            @Override
            public String id() {
                return "scalar-adapters";
            }

            @Override
            public void configure(JsonModuleContext context) {
                context.add(String.class, new JsonAdapter<>() {
                    @Override
                    public void write(String value, JsonWriter writer) {
                        writer.string("mapped");
                    }

                    @Override
                    public String read(JsonReader reader) {
                        String value = reader.text();
                        reader.skipValue();
                        return value;
                    }
                });
                context.add(Boolean.class, new JsonAdapter<>() {
                    @Override
                    public void write(Boolean value, JsonWriter writer) {
                        writer.bool(!value);
                    }

                    @Override
                    public Boolean read(JsonReader reader) {
                        Boolean value = Boolean.valueOf(reader.text());
                        reader.skipValue();
                        return value;
                    }
                });
            }
        };
        JsonMapper mapper = Json.builder().provider(provider).module(module).build();
        assertEquals("\"mapped\"", mapper.write("s"));
        assertEquals("false", mapper.write(true));
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        JsonWriteOptions options = JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE);
        String document = "[\"s\",true,false,null,2.00]";
        assertEquals(document, join(streams.writeEvents(streams.readEvents(split(document)), options)));
        assertEquals("[\"mapped\",false]", join(streams.writeEvents(Flux.just(
                JsonEvent.START_ARRAY, JsonEvent.value("s"), JsonEvent.value(true), JsonEvent.END_ARRAY), options)));
    }

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void boundValuesAndAllFramingsUseExactPerValueBudgets(JsonProvider provider) {
        JsonMapper mapper = Json.builder().provider(provider).build();
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        JsonWriteOptions single = new JsonWriteOptions(JsonWriteOptions.Format.SINGLE_VALUE, 2, 2);
        assertEquals("22", join(streams.writeBytes(Flux.just(22), single)));
        assertEquals("[1,22]", join(streams.writeBytes(Flux.just(1, 22),
                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 2, 2))));
        assertEquals("22\n", join(streams.writeBytes(Flux.just(22),
                new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 2, 2))));
        assertEquals("[1,22]", join(streams.writeEvents(
                Flux.just(JsonEvent.START_ARRAY, JsonEvent.value(1), JsonEvent.number("22"), JsonEvent.END_ARRAY), single)));
        StepVerifier.create(streams.writeEvents(Flux.just(JsonEvent.START_ARRAY, JsonEvent.value(1),
                        JsonEvent.value(333), JsonEvent.END_ARRAY), single))
                .thenConsumeWhile(bytes -> true).expectErrorMatches(StructuredStreamingTest::isLimit).verify();
        StepVerifier.create(streams.writeBytes(Flux.just(333), single))
                .expectErrorMatches(StructuredStreamingTest::isLimit).verify();
        StepVerifier.create(streams.writeBytes(Flux.just(1, 2), single))
                .thenConsumeWhile(bytes -> true).expectError(JsonException.class).verify();
        StepVerifier.create(streams.writeBytes(Flux.empty(), single)).expectError(JsonException.class).verify();
        assertThrows(IllegalArgumentException.class, () -> streams.writeEvents(Flux.empty(),
                JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)));
        assertEquals("{\"items\":[{\"n\":1}],\"tail\":null}", join(streams.writeEvents(Flux.just(
                JsonEvent.START_OBJECT, JsonEvent.name("items"), JsonEvent.START_ARRAY,
                JsonEvent.value(mapper.createObject(Map.of("n", 1))), JsonEvent.END_ARRAY,
                JsonEvent.name("tail"), JsonEvent.value(null), JsonEvent.END_OBJECT),
                JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE))));
        StepVerifier.create(streams.writeEventBuffers(Flux.just(JsonEvent.TRUE),
                        JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)))
                .expectNextMatches(ByteBuffer::isReadOnly).verifyComplete();
    }

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void invalidAndTruncatedDocumentsNeverBecomeSuccessfulOutput(JsonProvider provider) {
        JsonStreamMapper streams = JsonStreamMapper.of(Json.builder().provider(provider).build());
        List<List<JsonEvent>> invalid = List.of(List.of(), List.of(JsonEvent.END_ARRAY),
                List.of(JsonEvent.START_OBJECT, JsonEvent.name("x"), JsonEvent.END_OBJECT),
                List.of(JsonEvent.START_ARRAY, JsonEvent.END_OBJECT),
                List.of(JsonEvent.name("x")), List.of(JsonEvent.NULL, JsonEvent.NULL),
                List.of(JsonEvent.START_OBJECT, JsonEvent.string("x")),
                List.of(JsonEvent.START_OBJECT, JsonEvent.name("x"), JsonEvent.name("y")),
                List.of(JsonEvent.START_ARRAY), List.of(JsonEvent.number("1,2")));
        for (List<JsonEvent> events : invalid) {
            StepVerifier.create(streams.writeEvents(Flux.fromIterable(events),
                            JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)))
                    .thenConsumeWhile(bytes -> true).expectError(JsonException.class).verify();
        }
        for (String input : List.of("", "{", "1 2", "[true", "{\"x\":1")) {
            StepVerifier.create(streams.readEvents(split(input))).thenConsumeWhile(event -> true)
                    .expectError(JsonException.class).verify();
        }
        RuntimeException failure = new IllegalStateException("upstream");
        StepVerifier.create(streams.writeEvents(Flux.concat(Flux.just(JsonEvent.START_ARRAY), Flux.error(failure)),
                        JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)))
                .expectNextMatches(bytes -> Arrays.equals(bytes, new byte[]{'['}))
                .expectErrorMatches(error -> error == failure).verify();
        AtomicInteger produced = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<JsonEvent> source = Flux.<JsonEvent>concat(Flux.just(JsonEvent.START_ARRAY),
                Flux.range(0, 100).map(i -> JsonEvent.value(produced.incrementAndGet())),
                Flux.just(JsonEvent.END_ARRAY)).doOnCancel(() -> cancelled.set(true));
        StepVerifier.create(streams.writeEvents(source,
                        JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)), 1)
                .expectNextMatches(bytes -> bytes[0] == '[').thenCancel().verify();
        assertTrue(cancelled.get());
        assertTrue(produced.get() <= 1);
    }

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void structuralTokensRespectDepthTextAndByteLimits(JsonProvider provider) {
        JsonMapper mapper = Json.builder().provider(provider).options(
                new JsonOptions(true, false, false, false, true, 2, 3, 2, 20)).build();
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        JsonWriteOptions options = JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE);
        for (List<JsonEvent> events : List.<List<JsonEvent>>of(
                List.of(JsonEvent.START_ARRAY, JsonEvent.START_ARRAY, JsonEvent.START_ARRAY),
                List.of(JsonEvent.string("long")), List.of(JsonEvent.number("123")))) {
            StepVerifier.create(streams.writeEvents(Flux.fromIterable(events), options))
                    .thenConsumeWhile(bytes -> true).expectErrorMatches(StructuredStreamingTest::isLimit).verify();
        }
        StepVerifier.create(streams.readEvents(split("\"long\""))).expectErrorMatches(StructuredStreamingTest::isLimit)
                .verify();
        JsonMapper tiny = Json.builder().provider(provider).options(
                new JsonOptions(true, false, false, false, false, 10, 100, 100, 2)).build();
        StepVerifier.create(JsonStreamMapper.of(tiny).readEvents(split("22")))
                .expectNext(JsonEvent.number("22")).verifyComplete();
        StepVerifier.create(JsonStreamMapper.of(tiny).readEvents(split("123")))
                .expectErrorMatches(StructuredStreamingTest::isLimit).verify();
    }

    @ParameterizedTest
    @MethodSource(PROVIDERS)
    void megabyteValuesKeepExactBudgetsAndOwnedOutputChunks(JsonProvider provider) {
        JsonMapper mapper = Json.builder().provider(provider).build();
        JsonStreamMapper streams = JsonStreamMapper.of(mapper);
        String value = "x".repeat(1024 * 1024);
        int bytes = value.length() + 2;
        Flux<byte[]> output = streams.writeBytes(Flux.just(value, value),
                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 8192, bytes));
        String json = join(output.doOnNext(chunk -> assertTrue(chunk.length <= 8192)));
        assertEquals(List.of(value, value), mapper.read(json, List.class));
        StepVerifier.create(streams.writeBytes(Flux.just(value),
                        new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 8192, bytes - 1)))
                .thenConsumeWhile(chunk -> true).expectErrorMatches(StructuredStreamingTest::isLimit).verify();
    }

    private static boolean isLimit(Throwable error) {
        return error instanceof JsonException json && json.kind() == JsonException.Kind.LIMIT;
    }

    private static Flux<byte[]> split(String document) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        return Flux.range(0, bytes.length).map(i -> new byte[]{bytes[i]});
    }

    private static String join(Flux<byte[]> output) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StepVerifier.create(output.doOnNext(bytes::writeBytes)).thenConsumeWhile(chunk -> true).verifyComplete();
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
