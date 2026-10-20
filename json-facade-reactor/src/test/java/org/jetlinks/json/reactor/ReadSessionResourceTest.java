package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadSessionResourceTest {
    @Test
    void idleCancellationClosesOnceAndPreventsFurtherParserAccess() {
        GatedSession session = new GatedSession(Operation.READ);
        ReadSessionResource<Integer> resource = new ReadSessionResource<>(session, JsonReadOptions.NullPolicy.FAIL);
        resource.close();
        resource.close();
        resource.feed(ByteBuffer.allocate(0));
        resource.endInput();
        assertEquals(JsonReadSession.State.END, resource.read().state());
        assertTrue(resource.cancelled());
        assertEquals(1, session.closed.get());
        assertEquals(1, session.entered.getCount());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void cancellationReturnsBeforeTheActiveOperationAndCleanupRunsOnce(Operation operation) throws Exception {
        GatedSession session = new GatedSession(operation);
        ReadSessionResource<Integer> resource = new ReadSessionResource<>(session, JsonReadOptions.NullPolicy.FAIL);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> active = executor.submit(() -> invoke(resource, operation));
            assertTrue(session.entered.await(5, TimeUnit.SECONDS));
            // The parser remains exclusively owned even while cancellation can race with it.
            assertThrows(IllegalStateException.class, () -> resource.feed(ByteBuffer.allocate(0)));
            executor.submit(resource::close).get(2, TimeUnit.SECONDS);
            resource.close();
            assertTrue(resource.cancelled());
            assertEquals(0, session.closed.get());
            assertTrue(session.active.get());
            session.release.countDown();
            active.get(5, TimeUnit.SECONDS);
            resource.close();
            assertEquals(1, session.closed.get());
            assertFalse(session.active.get());
        } finally {
            session.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            resource.close();
        }
    }

    private static void invoke(ReadSessionResource<Integer> resource, Operation operation) {
        switch (operation) {
            case FEED -> resource.feed(ByteBuffer.allocate(0));
            case READ -> resource.read();
            case END_INPUT -> resource.endInput();
        }
    }

    private enum Operation { FEED, READ, END_INPUT }

    /** Tests only resource ownership; real parser/binding cancellation is covered by stream tests. */
    private static final class GatedSession implements JsonReadSession<Integer> {
        private final Operation operation;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean active = new AtomicBoolean();
        private final AtomicInteger closed = new AtomicInteger();

        private GatedSession(Operation operation) {
            this.operation = operation;
        }

        @Override
        public boolean needsInput() {
            return true;
        }

        @Override
        public void feed(ByteBuffer input) {
            enter(Operation.FEED);
        }

        @Override
        public Result<Integer> read() {
            enter(Operation.READ);
            return Result.needInput();
        }

        @Override
        public Result<Integer> read(BooleanSupplier cancelled) {
            enter(Operation.READ);
            return cancelled.getAsBoolean() ? Result.end() : Result.needInput();
        }

        @Override
        public void endInput() {
            enter(Operation.END_INPUT);
        }

        private void enter(Operation current) {
            assertEquals(operation, current);
            assertEquals(0, closed.get());
            active.set(true);
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                active.set(false);
            }
        }

        @Override
        public void close() {
            assertFalse(active.get());
            closed.incrementAndGet();
        }
    }
}
