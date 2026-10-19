package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStreamMapperTest {
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
}
