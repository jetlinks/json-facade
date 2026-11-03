package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyJsonStreamMapperTest {
    static Stream<JsonProvider> providers() {
        return Stream.of(new Jackson2Provider(), new Jackson3Provider());
    }

    static JsonMapper mapper(JsonProvider provider) {
        return Json.builder().provider(provider).build();
    }

    static ByteBuf bytes(String json) {
        return Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void borrowedReceiveRetainsBeforeSourceReleaseAndReleasesOnCancellation(JsonProvider provider) {
        ByteBuf input = bytes("[1,2,3]");
        AtomicInteger delivered = new AtomicInteger();
        Flux<ByteBuf> source = Flux.generate(sink -> {
            delivered.incrementAndGet();
            try {
                sink.next(input);
            } finally {
                input.release();
            }
            sink.complete();
        });
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), UnpooledByteBufAllocator.DEFAULT);
        StepVerifier.create(streams.read(source, Integer.class,
                        JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)), 0)
                .then(() -> assertEquals(0, delivered.get()))
                .thenRequest(1).expectNext(1)
                .then(() -> assertEquals(1, input.refCnt()))
                .thenCancel().verify();
        assertEquals(0, input.refCnt());
        assertEquals(1, delivered.get());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void inputKindsPreserveIndicesAndSplitUtf8AcrossCompositeComponents(JsonProvider provider) {
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), UnpooledByteBufAllocator.DEFAULT);
        byte[] encoded = "[\"中😀\",\"x\"]".getBytes(StandardCharsets.UTF_8);
        for (boolean direct : List.of(false, true)) {
            ByteBuf parent = direct ? Unpooled.directBuffer(encoded.length + 2) : Unpooled.buffer(encoded.length + 2);
            parent.writeByte('!').writeBytes(encoded).writeByte('?');
            ByteBuf input = parent.slice(1, encoded.length).asReadOnly();
            input.markReaderIndex();
            StepVerifier.create(streams.read(Flux.just(input), JsonType.of(String.class),
                            JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                    .expectNext("中😀", "x").verifyComplete();
            assertEquals(1, parent.refCnt());
            assertEquals(0, input.readerIndex());
            assertEquals(encoded.length, input.writerIndex());
            input.resetReaderIndex();
            parent.release();
        }
        var composite = Unpooled.compositeBuffer(encoded.length);
        for (byte value : encoded) {
            composite.addComponent(true, Unpooled.wrappedBuffer(new byte[]{value}));
        }
        StepVerifier.create(streams.readOwned(Flux.just(composite), String.class,
                        JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                .expectNext("中😀", "x").verifyComplete();
        assertEquals(0, composite.refCnt());
    }

    @Test
    void leasesExposeSharedStorageWithoutMergingAndCloseIdempotently() {
        ByteBuf first = bytes("a");
        ByteBuf second = bytes("b");
        var input = Unpooled.compositeBuffer().addComponents(true, first, second);
        NettyInputChunk chunk = new NettyInputChunk(input);
        ByteBuffer view = chunk.nextBuffer();
        first.setByte(0, 'x');
        assertEquals('x', view.get(0));
        assertEquals('b', chunk.nextBuffer().get(0));
        assertNull(chunk.nextBuffer());
        chunk.close();
        chunk.close();
        assertNull(chunk.nextBuffer());
        assertEquals(0, first.refCnt());
        assertEquals(0, second.refCnt());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void formatsSelectionNullsAndErrorsKeepProviderSemanticsAndRelease(JsonProvider provider) {
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), UnpooledByteBufAllocator.DEFAULT);
        for (JsonReadOptions.Mode mode : List.of(JsonReadOptions.Mode.NDJSON, JsonReadOptions.Mode.ROOT_VALUES)) {
            ByteBuf input = bytes("1\n2\n");
            StepVerifier.create(streams.readOwned(Flux.just(Unpooled.EMPTY_BUFFER, input), JsonType.of(Integer.class),
                    JsonReadOptions.of(mode))).expectNext(1, 2).verifyComplete();
            assertEquals(0, input.refCnt());
        }
        ByteBuf selected = bytes("{\"meta\":0,\"items\":[1,2],\"tail\":true}");
        JsonReadOptions options = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                new JsonReadOptions.Selection(JsonPointer.of("/items"), true), JsonReadOptions.NullPolicy.FAIL, 100);
        StepVerifier.create(streams.readOwned(Flux.just(selected), JsonType.of(Integer.class), options))
                .expectNext(1, 2).verifyComplete();
        assertEquals(0, selected.refCnt());
        for (String json : List.of("[1,", "[null]", "[!]") ) {
            ByteBuf invalid = bytes(json);
            StepVerifier.create(streams.readOwned(Flux.just(invalid), JsonType.of(Integer.class),
                            JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                    .thenConsumeWhile(v -> true).expectError(JsonException.class).verify();
            assertEquals(0, invalid.refCnt());
        }
        RuntimeException failure = new IllegalStateException("source");
        ByteBuf partial = bytes("[1,");
        StepVerifier.create(streams.readOwned(Flux.concat(Flux.just(partial), Flux.error(failure)),
                        JsonType.of(Integer.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                .expectNext(1).expectErrorMatches(error -> error == failure).verify();
        assertEquals(0, partial.refCnt());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void structuralInputOutputAndMultipleSubscriptionsShareOnlyConfiguration(JsonProvider provider) {
        TrackingAllocator allocator = new TrackingAllocator(true);
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), allocator);
        String document = "{\"items\":[{\"s\":\"中\"},null,1.20],\"tail\":true}";
        Flux<JsonEvent> events = streams.readOwnedEvents(Flux.defer(() -> Flux.just(bytes(document))));
        Flux<ByteBuf> output = streams.writeEvents(events,
                new JsonWriteOptions(JsonWriteOptions.Format.SINGLE_VALUE, 3, 100));
        assertEquals(document, join(output));
        assertEquals(document, join(output));
        ByteBuf borrowed = bytes("{}");
        StepVerifier.create(streams.readEvents(Flux.just(borrowed)))
                .expectNext(JsonEvent.START_OBJECT, JsonEvent.END_OBJECT).verifyComplete();
        assertEquals(1, borrowed.refCnt());
        borrowed.release();
        allocator.verifyReleased();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void outputFormatsTransferIndependentBuffersAndUseAllocatorPreference(JsonProvider provider) {
        JsonMapper mapper = mapper(provider);
        for (boolean direct : List.of(false, true)) {
            TrackingAllocator allocator = new TrackingAllocator(direct);
            NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, allocator);
            for (JsonWriteOptions.Format format : JsonWriteOptions.Format.values()) {
                Flux<Integer> values = format == JsonWriteOptions.Format.SINGLE_VALUE ? Flux.just(1) : Flux.just(1, 2);
                List<ByteBuf> retained = new ArrayList<>();
                StepVerifier.create(streams.write(values, new JsonWriteOptions(format, 3, 100)), 0)
                        .thenRequest(1).assertNext(buffer -> {
                            assertEquals(direct, buffer.isDirect());
                            assertTrue(buffer.readableBytes() <= 3);
                            assertEquals(1, buffer.refCnt());
                            retained.add(buffer);
                        }).thenRequest(Long.MAX_VALUE).thenConsumeWhile(b -> {
                            retained.add(b);
                            return true;
                        }).verifyComplete();
                String expected = switch (format) {
                    case ARRAY -> "[1,2]";
                    case SINGLE_VALUE -> "1";
                    case NDJSON, ROOT_VALUES -> "1\n2\n";
                };
                assertEquals(expected, join(Flux.fromIterable(retained)));
            }
            allocator.verifyReleased();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void outputErrorsCancellationAndDownstreamDiscardsReleaseEveryOwnedReference(JsonProvider provider) {
        TrackingAllocator allocator = new TrackingAllocator(false);
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), allocator);
        assertEquals("[", join(streams.write(Flux.range(0, 100),
                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 1, 100)).take(1)));
        join(streams.write(Flux.just("x".repeat(10000)),
                new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 8, 20000)).take(1));
        StepVerifier.create(streams.write(Flux.just("too long"),
                        new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 1, 2)))
                .expectError(JsonException.class).verify();
        StepVerifier.create(streams.write(Flux.just("x".repeat(30000)),
                        new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 8, 10000)))
                .expectError(JsonException.class).verify();
        StepVerifier.create(streams.writeEvents(Flux.just(JsonEvent.START_OBJECT),
                        JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)).doOnNext(ByteBuf::release))
                .expectNextCount(1).expectError(JsonException.class).verify();
        assertEquals("", join(streams.write(Flux.empty(), JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON))));
        assertEquals("[]", join(streams.write(Flux.empty(), JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY))));
        StepVerifier.create(streams.write(Flux.empty(), JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)))
                .expectError(JsonException.class).verify();
        StepVerifier.create(streams.write(Flux.just(1, 2), JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE))
                        .doOnNext(ByteBuf::release))
                .expectNextCount(1).expectError(JsonException.class).verify();
        assertFalse(streams.write(Flux.just(1), JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON))
                .filter(b -> false).doOnDiscard(ByteBuf.class, ByteBuf::release).hasElements().block());
        allocator.verifyReleased();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void inFlightInputDiscardAndUnsupportedViewsReleaseOnlyAdapterOwnership(JsonProvider provider) {
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper(provider), UnpooledByteBufAllocator.DEFAULT);
        TestPublisher<ByteBuf> source = TestPublisher.createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);
        ByteBuf input = bytes("[1]");
        var subscription = streams.readOwned(source, JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).subscribe();
        subscription.dispose();
        source.next(input);
        assertEquals(0, input.refCnt());
        ByteBuf unsupported = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 10, 10) {
            @Override
            public int nioBufferCount() {
                return 0;
            }
        };
        unsupported.writeByte('1');
        StepVerifier.create(streams.readOwned(Flux.just(unsupported), JsonType.of(Integer.class),
                        JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE)))
                .expectError(UnsupportedOperationException.class).verify();
        assertEquals(0, unsupported.refCnt());
    }

    static String join(Flux<ByteBuf> output) {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        StepVerifier.create(output.doOnNext(buffer -> {
            try {
                text.writeBytes(ByteBufUtil.getBytes(buffer));
            } finally {
                buffer.release();
            }
        })).thenConsumeWhile(b -> true).verifyComplete();
        return text.toString(StandardCharsets.UTF_8);
    }

    @Test
    void invalidArgumentsFailBeforeSubscription() {
        JsonMapper mapper = mapper(new Jackson2Provider());
        assertThrows(NullPointerException.class, () -> NettyJsonStreamMapper.of(mapper, null));
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, UnpooledByteBufAllocator.DEFAULT);
        assertThrows(NullPointerException.class, () -> streams.readEvents(null));
        assertThrows(IllegalArgumentException.class, () -> streams.writeEvents(Flux.empty(),
                JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)));
    }
}
