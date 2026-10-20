package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
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
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyCancellationTest {
    static Stream<JsonProvider> providers() {
        return NettyJsonStreamMapperTest.providers();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void activeInputLeaseOutlivesCancelAndIsReleasedWhenNativeCallbackReturns(JsonProvider provider) throws Exception {
        Gate gate = new Gate();
        TrackingAllocator allocator = new TrackingAllocator(false);
        JsonMapper mapper = Json.builder().provider(provider).module(gate).build();
        NettyJsonStreamMapper streams = NettyJsonStreamMapper.of(mapper, allocator);
        ByteBuf input = NettyJsonStreamMapperTest.bytes("[1,2]");
        DeferredSubscriber<Item> subscriber = new DeferredSubscriber<>();
        streams.readOwned(Flux.just(input), JsonType.of(Item.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).subscribe(subscriber);
        cancelDuring(gate, subscriber, () -> assertEquals(1, input.refCnt()));
        assertEquals(0, input.refCnt());
        assertEquals(0, subscriber.delivered.get());
        assertNull(subscriber.failure.get());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void activeSerializerStopsPublishingAndDiscardsOutputAfterCancel(JsonProvider provider) throws Exception {
        Gate gate = new Gate();
        TrackingAllocator allocator = new TrackingAllocator(true);
        JsonMapper mapper = Json.builder().provider(provider).module(gate).build();
        DeferredSubscriber<ByteBuf> subscriber = new DeferredSubscriber<>();
        NettyJsonStreamMapper.of(mapper, allocator).write(Flux.just(new Item(1)),
                new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 1, 100)).subscribe(subscriber);
        cancelDuring(gate, subscriber, () -> assertEquals(0, subscriber.delivered.get()));
        assertEquals(0, subscriber.delivered.get());
        assertNull(subscriber.failure.get());
        allocator.verifyReleased();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationRacingFirstOutputDemandLeavesNoUnpublishedReference(JsonProvider provider) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            JsonMapper mapper = NettyJsonStreamMapperTest.mapper(provider);
            for (int i = 0; i < 100; i++) {
                TrackingAllocator allocator = new TrackingAllocator(false);
                DeferredSubscriber<ByteBuf> subscriber = new DeferredSubscriber<>();
                NettyJsonStreamMapper.of(mapper, allocator).write(Flux.just("x".repeat(1000)),
                        new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 16, 2000)).subscribe(subscriber);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> request = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    subscriber.request(1);
                    return null;
                });
                Future<?> cancel = executor.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    subscriber.cancel();
                    return null;
                });
                request.get(5, TimeUnit.SECONDS);
                cancel.get(5, TimeUnit.SECONDS);
                assertNull(subscriber.failure.get());
                allocator.verifyReleased();
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void cancelDuring(Gate gate, BaseSubscriber<?> subscriber, Runnable whileActive) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> active = executor.submit(() -> subscriber.request(1));
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            executor.submit(subscriber::cancel).get(2, TimeUnit.SECONDS);
            whileActive.run();
            assertEquals(1, gate.release.getCount());
            gate.release.countDown();
            active.get(5, TimeUnit.SECONDS);
        } finally {
            gate.release.countDown();
            subscriber.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private record Item(int value) { }

    /** Explicit latches observe callback lifetime without relying on timing or sleeps. */
    private static final class Gate implements JsonModule, JsonAdapter<Item> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "netty-cancellation";
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
        public void write(Item value, JsonWriter writer) {
            // Ensure there are unpublished buffers before the callback is cancelled.
            writer.beginArray();
            writer.value(1);
            writer.flush();
            await();
            writer.value(value.value());
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
            // The executor drives request so cancellation can race with that thread.
        }

        @Override
        protected void hookOnNext(T item) {
            delivered.incrementAndGet();
            if (item instanceof ByteBuf buffer) {
                buffer.release();
            }
        }

        @Override
        protected void hookOnError(Throwable error) {
            failure.set(error);
        }
    }
}
