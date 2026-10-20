package org.jetlinks.json.spring;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
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
import org.springframework.core.io.buffer.CloseableDataBuffer;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferWrapper;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBufferJsonStreamMapperTest {
    static Stream<JsonProvider> providers() {
        return Stream.of(new Jackson2Provider(), new Jackson3Provider());
    }

    static JsonMapper mapper(JsonProvider provider) {
        return Json.builder().provider(provider).build();
    }

    static DataBuffer bytes(String json) {
        return DefaultDataBufferFactory.sharedInstance.wrap(json.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void demandTransfersInputAndCancellationClosesIteratorBeforeBuffer(JsonProvider provider) {
        ObservedBuffer input = new ObservedBuffer(bytes("[1,2,3]"), false);
        AtomicInteger delivered = new AtomicInteger();
        DataBufferJsonStreamMapper streams = DataBufferJsonStreamMapper.of(mapper(provider),
                DefaultDataBufferFactory.sharedInstance);
        StepVerifier.create(streams.read(Flux.just(input).doOnNext(buffer -> delivered.incrementAndGet()),
                JsonType.of(Integer.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)), 0)
                .then(() -> assertEquals(0, delivered.get()))
                .thenRequest(1).expectNext(1)
                .then(() -> {
                    assertEquals(1, input.opened);
                    assertEquals(0, input.closed);
                    assertEquals(0, input.released);
                }).thenCancel().verify();
        input.verifyReleased();
        assertEquals(1, delivered.get());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void factoriesIndicesAndCompositeUtf8ViewsKeepInputStorage(JsonProvider provider) {
        byte[] encoded = "[\"中😀\",\"x\"]".getBytes(StandardCharsets.UTF_8);
        for (boolean direct : List.of(false, true)) {
            var ordinary = new DefaultDataBufferFactory(direct);
            DataBuffer buffer = ordinary.allocateBuffer(encoded.length + 2);
            buffer.write((byte) '!').write(encoded).write((byte) '?');
            buffer.readPosition(1).writePosition(encoded.length + 1);
            ObservedBuffer input = new ObservedBuffer(buffer, false);
            DataBufferJsonStreamMapper streams = DataBufferJsonStreamMapper.of(mapper(provider), ordinary);
            StepVerifier.create(streams.read(Flux.just(input), JsonType.of(String.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                    .expectNext("中😀", "x").verifyComplete();
            assertEquals(1, buffer.readPosition());
            assertEquals(encoded.length + 1, buffer.writePosition());
            input.verifyReleased();
        }
        TrackingFactory factory = new TrackingFactory(true);
        for (boolean direct : List.of(false, true)) {
            ByteBuf parent = direct ? Unpooled.directBuffer(encoded.length + 2) : Unpooled.buffer(encoded.length + 2);
            parent.writeByte('!').writeBytes(encoded).writeByte('?');
            ByteBuf slice = parent.retainedSlice(1, encoded.length).asReadOnly();
            try {
                StepVerifier.create(DataBufferJsonStreamMapper.of(mapper(provider), factory)
                        .read(Flux.just(factory.wrap(slice)), JsonType.of(String.class),
                                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                        .expectNext("中😀", "x").verifyComplete();
                assertEquals(1, parent.refCnt());
                assertEquals(0, slice.readerIndex());
                assertEquals(encoded.length, slice.writerIndex());
            } finally {
                parent.release();
            }
        }
        var composite = Unpooled.compositeBuffer(encoded.length);
        for (byte value : encoded) {
            composite.addComponent(true, Unpooled.wrappedBuffer(new byte[]{value}));
        }
        StepVerifier.create(DataBufferJsonStreamMapper.of(mapper(provider), factory)
                .read(Flux.just(factory.wrap(composite)), JsonType.of(String.class),
                        JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                .expectNext("中😀", "x").verifyComplete();
        assertEquals(0, composite.refCnt());
    }

    @Test
    void leaseSharesStorageAndReleasesEvenWhenIteratorCloseFails() {
        byte[] storage = new byte[]{'a', 'b'};
        ObservedBuffer input = new ObservedBuffer(DefaultDataBufferFactory.sharedInstance.wrap(storage), false);
        DataBufferInputChunk chunk = new DataBufferInputChunk(input);
        ByteBuffer view = chunk.nextBuffer();
        storage[0] = 'x';
        assertEquals('x', view.get(0));
        assertNull(chunk.nextBuffer());
        chunk.close();
        chunk.close();
        assertNull(chunk.nextBuffer());
        input.verifyReleased();
        ObservedBuffer failing = new ObservedBuffer(bytes("x"), true);
        DataBufferInputChunk failure = new DataBufferInputChunk(failing);
        failure.nextBuffer();
        assertThrows(IllegalStateException.class, failure::close);
        failure.close();
        failing.verifyReleased();
        ObservedBuffer unopened = new ObservedBuffer(bytes("x"), false);
        new DataBufferInputChunk(unopened).close();
        assertEquals(0, unopened.opened);
        assertEquals(1, unopened.released);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void formatsSelectionAndFailuresReleaseDeliveredInput(JsonProvider provider) {
        var streams = DataBufferJsonStreamMapper.of(mapper(provider), DefaultDataBufferFactory.sharedInstance);
        for (var mode : List.of(JsonReadOptions.Mode.NDJSON, JsonReadOptions.Mode.ROOT_VALUES)) {
            ObservedBuffer input = new ObservedBuffer(bytes("1\n2\n"), false);
            StepVerifier.create(streams.read(Flux.just(bytes(""), input), JsonType.of(Integer.class),
                    JsonReadOptions.of(mode))).expectNext(1, 2).verifyComplete();
            input.verifyReleased();
        }
        var selection = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                new JsonReadOptions.Selection(JsonPointer.of("/items"), true), JsonReadOptions.NullPolicy.FAIL, 100);
        StepVerifier.create(streams.read(Flux.just(bytes("{\"items\":[1,2],\"tail\":true}")),
                JsonType.of(Integer.class), selection)).expectNext(1, 2).verifyComplete();
        for (String json : List.of("[1,", "[null]", "[!]")) {
            ObservedBuffer input = new ObservedBuffer(bytes(json), false);
            StepVerifier.create(streams.read(Flux.just(input), JsonType.of(Integer.class),
                            JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                    .thenConsumeWhile(v -> true).expectError(JsonException.class).verify();
            input.verifyReleased();
        }
        ObservedBuffer partial = new ObservedBuffer(bytes("[1,"), false);
        RuntimeException failure = new IllegalStateException("source");
        StepVerifier.create(streams.read(Flux.concat(Flux.just(partial), Flux.error(failure)),
                JsonType.of(Integer.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)))
                .expectNext(1).expectErrorMatches(error -> error == failure).verify();
        partial.verifyReleased();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void eventsAndFormatsTransferBoundedOutputAndKeepSubscriptionsIndependent(JsonProvider provider) {
        for (boolean direct : List.of(false, true)) {
            TrackingFactory factory = new TrackingFactory(direct);
            var streams = DataBufferJsonStreamMapper.of(mapper(provider), factory);
            String document = "{\"items\":[{\"s\":\"中\"},null,1.20],\"tail\":true}";
            Flux<DataBuffer> copied = streams.writeEvents(streams.readEvents(Flux.defer(() -> Flux.just(bytes(document)))),
                    new JsonWriteOptions(JsonWriteOptions.Format.SINGLE_VALUE, 3, 100));
            assertEquals(document, join(copied));
            assertEquals(document, join(copied));
            for (JsonWriteOptions.Format format : JsonWriteOptions.Format.values()) {
                Flux<Integer> values = format == JsonWriteOptions.Format.SINGLE_VALUE ? Flux.just(1) : Flux.just(1, 2);
                List<DataBuffer> retained = new ArrayList<>();
                StepVerifier.create(streams.write(values, new JsonWriteOptions(format, 3, 100)), 0)
                        .thenRequest(1).assertNext(buffer -> {
                            assertEquals(direct, ((NettyDataBuffer) buffer).getNativeBuffer().isDirect());
                            assertTrue(buffer.readableByteCount() <= 3);
                            retained.add(buffer);
                        }).thenRequest(Long.MAX_VALUE).thenConsumeWhile(buffer -> retained.add(buffer)).verifyComplete();
                String expected = switch (format) {
                    case ARRAY -> "[1,2]";
                    case SINGLE_VALUE -> "1";
                    case NDJSON, ROOT_VALUES -> "1\n2\n";
                };
                assertEquals(expected, join(Flux.fromIterable(retained)));
            }
            factory.verifyReleased();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void outputCancellationErrorsAndLateInputDiscardDoNotLeak(JsonProvider provider) {
        TrackingFactory factory = new TrackingFactory(false);
        var streams = DataBufferJsonStreamMapper.of(mapper(provider), factory);
        assertEquals("[", join(streams.write(Flux.range(0, 100),
                new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 1, 100)).take(1)));
        join(streams.write(Flux.just("x".repeat(10000)),
                new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 8, 20000)).take(1));
        StepVerifier.create(streams.write(Flux.just("x".repeat(30000)),
                new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 8, 10000)))
                .expectError(JsonException.class).verify();
        StepVerifier.create(streams.writeEvents(Flux.just(JsonEvent.START_OBJECT),
                JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE)).doOnNext(DataBufferUtils::release))
                .expectNextCount(1).expectError(JsonException.class).verify();
        assertFalse(streams.write(Flux.just(1), JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON))
                .filter(buffer -> false).doOnDiscard(DataBuffer.class, DataBufferUtils::release).hasElements().block());
        TestPublisher<DataBuffer> source = TestPublisher.createNoncompliant(TestPublisher.Violation.DEFER_CANCELLATION);
        var subscription = streams.read(source, JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).subscribe();
        subscription.dispose();
        ObservedBuffer late = new ObservedBuffer(bytes("[1]"), false);
        source.next(late);
        assertEquals(1, late.released);
        factory.verifyReleased();
    }

    static String join(Flux<DataBuffer> buffers) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        buffers.doOnNext(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                result.writeBytes(bytes);
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).blockLast();
        return result.toString(StandardCharsets.UTF_8);
    }

    static final class TrackingFactory extends NettyDataBufferFactory {
        private final List<ByteBuf> allocated = new java.util.concurrent.CopyOnWriteArrayList<>();

        TrackingFactory(boolean direct) {
            super(new UnpooledByteBufAllocator(direct));
        }

        @Override
        public NettyDataBuffer allocateBuffer(int capacity) {
            NettyDataBuffer buffer = super.allocateBuffer(capacity);
            allocated.add(buffer.getNativeBuffer());
            return buffer;
        }

        void verifyReleased() {
            allocated.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        }
    }

    /** Observes the public iterator/close contract without inspecting native implementation fields. */
    static final class ObservedBuffer extends DataBufferWrapper implements CloseableDataBuffer {
        private final boolean failClose;
        int opened;
        int closed;
        int released;

        ObservedBuffer(DataBuffer delegate, boolean failClose) {
            super(delegate);
            this.failClose = failClose;
        }

        @Override
        public ByteBufferIterator readableByteBuffers() {
            ByteBufferIterator delegate = super.readableByteBuffers();
            opened++;
            return new ByteBufferIterator() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public ByteBuffer next() {
                    return delegate.next();
                }

                @Override
                public void close() {
                    delegate.close();
                    closed++;
                    if (failClose) {
                        throw new IllegalStateException("iterator-close");
                    }
                }
            };
        }

        @Override
        public void close() {
            assertEquals(opened, closed, "Released while component views were still borrowed");
            released++;
            DataBufferUtils.release(dataBuffer());
        }

        void verifyReleased() {
            assertEquals(1, opened);
            assertEquals(1, closed);
            assertEquals(1, released);
        }
    }
}
