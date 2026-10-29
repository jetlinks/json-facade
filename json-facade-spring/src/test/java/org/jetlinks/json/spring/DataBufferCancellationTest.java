package org.jetlinks.json.spring;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBufferCancellationTest {
    static Stream<JsonProvider> providers() {
        return DataBufferJsonStreamMapperTest.providers();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void activeNativeCallbacksKeepViewsAndUnpublishedOutputAliveUntilReturn(JsonProvider provider) throws Exception {
        for (boolean writing : new boolean[]{false, true}) {
            Gate gate = new Gate();
            var factory = new DataBufferJsonStreamMapperTest.TrackingFactory(true);
            var mapper = Json.builder().provider(provider).module(gate).build();
            var streams = DataBufferJsonStreamMapper.of(mapper, factory);
            var input = new DataBufferJsonStreamMapperTest.ObservedBuffer(
                    DataBufferJsonStreamMapperTest.bytes("[1,2]"), false);
            var subscriber = new DeferredSubscriber<>();
            if (writing) {
                streams.write(Flux.just(new Item(1)),
                        new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 1, 100)).subscribe(subscriber);
            } else {
                streams.read(Flux.just(input), JsonType.of(Item.class),
                        JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).subscribe(subscriber);
            }
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> active = executor.submit(() -> subscriber.request(1));
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
                executor.submit(subscriber::cancel).get(2, TimeUnit.SECONDS);
                if (!writing) {
                    // Neither resource may close while this binding callback still borrows the view.
                    assertEquals(1, input.opened);
                    assertEquals(0, input.closed);
                    assertEquals(0, input.released);
                }
                gate.release.countDown();
                active.get(5, TimeUnit.SECONDS);
                assertEquals(0, subscriber.delivered.get());
                assertNull(subscriber.failure.get());
                if (!writing) {
                    input.verifyReleased();
                }
                factory.verifyReleased();
            } finally {
                gate.release.countDown();
                subscriber.cancel();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    private record Item(int value) { }

    /** Public callbacks expose native resource lifetime without timing assumptions. */
    private static final class Gate implements JsonModule, JsonAdapter<Item> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "databuffer-cancellation";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(Item.class, this);
        }

        @Override
        public Item read(JsonReader reader) {
            await();
            return new Item(reader.readValue(JsonType.of(Integer.class)));
        }

        @Override
        public void write(Item item, JsonWriter writer) {
            writer.beginArray();
            writer.value(1);
            writer.flush();
            await();
            writer.value(item.value());
            writer.endArray();
        }

        private void await() {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }
    }

    private static final class DeferredSubscriber<T> extends BaseSubscriber<T> {
        private final AtomicInteger delivered = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // A worker issues demand so the test can cancel during the callback.
        }

        @Override
        protected void hookOnNext(T item) {
            delivered.incrementAndGet();
            if (item instanceof DataBuffer buffer) {
                DataBufferUtils.release(buffer);
            }
        }

        @Override
        protected void hookOnError(Throwable error) {
            failure.set(error);
        }
    }
}
