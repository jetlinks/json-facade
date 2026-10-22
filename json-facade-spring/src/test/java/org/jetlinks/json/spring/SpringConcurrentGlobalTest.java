package org.jetlinks.json.spring;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.springframework.beans.factory.config.NamedBeanHolder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringConcurrentGlobalTest {
    @Test
    void businessInitializationAndExternalFirstLookupShareTheSpringSingletonWithoutLockInversion() throws Exception {
        CountDownLatch businessEntered = new CountDownLatch(1);
        CountDownLatch resolving = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger names = new AtomicInteger();
        var context = new AnnotationConfigApplicationContext(new ObservedFactory(resolving));
        context.register(JsonFacadeConfiguration.class);
        // Ordinary business initialization owns Spring's singleton lock when the external lookup starts.
        context.registerBean("business", Business.class, () -> {
            businessEntered.countDown();
            await(resolving);
            return new Business(Json.global());
        });
        context.registerBean("nativeMapper", ObjectMapper.class, () -> {
            builds.incrementAndGet();
            return new ObjectMapper();
        }, definition -> definition.setLazyInit(true));
        var executor = Executors.newFixedThreadPool(2, task -> {
            // A normal shared pool prefix exercises Spring 6.2's default singleton locking path.
            Thread thread = new Thread(task, "global-bootstrap-worker-" + names.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        boolean refreshed = false;
        try {
            var refresh = executor.submit(context::refresh);
            await(businessEntered);
            var lookup = executor.submit(() -> Json.global());
            refresh.get(10, TimeUnit.SECONDS);
            refreshed = true;
            JsonMapper mapper = lookup.get(10, TimeUnit.SECONDS);
            assertSame(mapper, context.getBean(JsonMapper.class));
            assertSame(mapper, context.getBean(Business.class).mapper());
            assertEquals(1, builds.get());
            assertEquals("{\"n\":1}", mapper.write(Map.of("n", 1)));
            context.close();
            assertSame(mapper, Json.global());
        } finally {
            resolving.countDown();
            executor.shutdownNow();
            // On a lock-order regression, do not close a factory whose lock is held by a blocked daemon.
            if (refreshed) {
                context.close();
            }
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private record Business(JsonMapper mapper) {
    }

    /** Observe the real public resolution boundary without replacing Spring singleton behavior. */
    private static final class ObservedFactory extends DefaultListableBeanFactory {
        private static final long serialVersionUID = 1L;
        private final transient CountDownLatch resolving;

        ObservedFactory(CountDownLatch resolving) {
            this.resolving = resolving;
        }

        @Override
        public <T> NamedBeanHolder<T> resolveNamedBean(Class<T> type) {
            if (type == JsonMapper.class) {
                resolving.countDown();
            }
            return super.resolveNamedBean(type);
        }
    }
}
