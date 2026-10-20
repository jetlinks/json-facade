package org.jetlinks.json;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalJsonMappersTest {
    @Test
    void explicitDefaultsAndNamesAreIndependentAndCannotBeReplaced() {
        AtomicInteger builds = new AtomicInteger();
        JsonMapper first = mapper();
        JsonMapper second = mapper();
        GlobalJsonMappers globals = new GlobalJsonMappers(() -> {
            builds.incrementAndGet();
            return second;
        });
        globals.register(first);
        globals.register("default", second);
        globals.register("Default", first);
        assertSame(first, globals.mapper());
        assertSame(second, globals.mapper("default"));
        assertSame(first, globals.mapper("Default"));
        assertEquals(0, builds.get());
        for (JsonMapper duplicate : List.of(first, second)) {
            assertConfiguration(() -> globals.register(duplicate));
            assertConfiguration(() -> globals.register("default", duplicate));
        }
        assertConfiguration(() -> globals.mapper("missing"));
        assertSame(first, globals.mapper());
        assertSame(second, globals.mapper("default"));
    }

    @Test
    void invalidArgumentsNeverCreateOrReserveInstances() {
        GlobalJsonMappers globals = new GlobalJsonMappers(GlobalJsonMappersTest::mapper);
        JsonMapper mapper = mapper();
        assertThrows(NullPointerException.class, () -> new GlobalJsonMappers(null));
        assertThrows(NullPointerException.class, () -> globals.mapper(null));
        assertThrows(NullPointerException.class, () -> globals.register(null, mapper));
        assertThrows(NullPointerException.class, () -> globals.register(null));
        assertThrows(NullPointerException.class, () -> globals.registerFactory(null));
        assertThrows(NullPointerException.class, () -> globals.register("valid", null));
        for (String invalid : List.of("", " ", "\t", " name", "name ", "name\n", "\u2003name")) {
            assertThrows(IllegalArgumentException.class, () -> globals.mapper(invalid));
            assertThrows(IllegalArgumentException.class, () -> globals.register(invalid, mapper));
        }
        globals.register("valid", mapper);
        globals.register(mapper);
        assertSame(mapper, globals.mapper("valid"));
        assertSame(mapper, globals.mapper());
    }

    @Test
    void deferredFactoryReservesTheDefaultWithoutBuildingAndRetainsFailedFactoryForRetry() {
        JsonMapper mapper = mapper();
        AtomicInteger builds = new AtomicInteger();
        JsonException failure = new JsonException(JsonException.Kind.CONFIGURATION, "not ready");
        GlobalJsonMappers globals = new GlobalJsonMappers(() -> { throw new AssertionError("discovery"); });
        globals.registerFactory(() -> {
            if (builds.incrementAndGet() == 1) {
                throw failure;
            }
            return mapper;
        });
        assertEquals(0, builds.get());
        assertConfiguration(() -> globals.register(mapper));
        assertConfiguration(() -> globals.registerFactory(() -> mapper));
        assertSame(failure, assertThrows(JsonException.class, globals::mapper));
        assertConfiguration(() -> globals.register(mapper));
        assertSame(mapper, globals.mapper());
        assertSame(mapper, globals.mapper());
        assertEquals(2, builds.get());
        assertConfiguration(() -> globals.registerFactory(() -> mapper));

        GlobalJsonMappers recursive = new GlobalJsonMappers(GlobalJsonMappersTest::mapper);
        recursive.registerFactory(recursive::mapper);
        assertConfiguration(recursive::mapper);
        assertConfiguration(() -> recursive.register(mapper));
    }

    @Test
    void constructionFailureKeepsItsIdentityAndAllowsRetryOrExplicitRegistration() {
        JsonException failure = new JsonException(JsonException.Kind.PROVIDER, "fixture");
        JsonMapper mapper = mapper();
        AtomicInteger builds = new AtomicInteger();
        GlobalJsonMappers retry = new GlobalJsonMappers(() -> {
            if (builds.incrementAndGet() == 1) {
                throw failure;
            }
            return mapper;
        });
        assertSame(failure, assertThrows(JsonException.class, retry::mapper));
        assertSame(mapper, retry.mapper());
        assertSame(mapper, retry.mapper());
        assertEquals(2, builds.get());
        assertConfiguration(() -> retry.register(mapper));

        GlobalJsonMappers register = new GlobalJsonMappers(() -> { throw failure; });
        assertSame(failure, assertThrows(JsonException.class, register::mapper));
        register.register(mapper);
        assertSame(mapper, register.mapper());

        GlobalJsonMappers invalid = new GlobalJsonMappers(() -> null);
        assertThrows(NullPointerException.class, invalid::mapper);
        invalid.register(mapper);
        assertSame(mapper, invalid.mapper());
    }

    @Test
    void recursiveInitializationAndRegistrationDoNotPublishPartialDefaults() {
        AtomicReference<GlobalJsonMappers> reference = new AtomicReference<>();
        GlobalJsonMappers recursive = new GlobalJsonMappers(() -> reference.get().mapper());
        reference.set(recursive);
        assertConfiguration(recursive::mapper);
        JsonMapper mapper = mapper();
        recursive.register(mapper);
        assertSame(mapper, recursive.mapper());

        GlobalJsonMappers registering = new GlobalJsonMappers(() -> {
            reference.get().register(mapper);
            return mapper;
        });
        reference.set(registering);
        assertConfiguration(registering::mapper);
        registering.register(mapper);
        assertSame(mapper, registering.mapper());
    }

    @Test
    void concurrentDefaultReadsBuildAndPublishOnlyOnce() throws Exception {
        JsonMapper mapper = mapper();
        AtomicInteger builds = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        GlobalJsonMappers globals = new GlobalJsonMappers(() -> {
            builds.incrementAndGet();
            return mapper;
        });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<JsonMapper>> reads = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                reads.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return globals.mapper();
                }));
            }
            await(ready);
            start.countDown();
            for (Future<JsonMapper> read : reads) {
                assertSame(mapper, read.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, builds.get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void registrationRacingAnActiveDefaultBuildCannotReplaceItsResult() throws Exception {
        JsonMapper built = mapper();
        JsonMapper explicit = mapper();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch registering = new CountDownLatch(1);
        GlobalJsonMappers globals = new GlobalJsonMappers(() -> {
            entered.countDown();
            await(release);
            return built;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JsonMapper> read = executor.submit(() -> globals.mapper());
            await(entered);
            Future<?> registration = executor.submit(() -> {
                registering.countDown();
                globals.register(explicit);
            });
            await(registering);
            assertFalse(registration.isDone());
            release.countDown();
            assertSame(built, read.get(10, TimeUnit.SECONDS));
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> registration.get(10, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof JsonException);
            assertEquals(JsonException.Kind.CONFIGURATION, ((JsonException) error.getCause()).kind());
            assertSame(built, globals.mapper());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentNamedRegistrationsPublishOneWinnerWithoutDefaultDiscovery() throws Exception {
        GlobalJsonMappers globals = new GlobalJsonMappers(() -> {
            throw new AssertionError("Named operations must not discover a default Provider");
        });
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> registrations = new ArrayList<>();
            List<JsonMapper> candidates = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                JsonMapper candidate = mapper();
                candidates.add(candidate);
                registrations.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        globals.register("shared", candidate);
                        return true;
                    } catch (JsonException error) {
                        assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
                        return false;
                    }
                }));
            }
            await(ready);
            start.countDown();
            int winners = 0;
            for (int index = 0; index < registrations.size(); index++) {
                if (registrations.get(index).get(10, TimeUnit.SECONDS)) {
                    winners++;
                    assertSame(candidates.get(index), globals.mapper("shared"));
                }
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void publicAccessorsAcceptCustomImplementationsAfterDefaultDiscoveryFailure() {
        // The API module intentionally has no Provider; failure must not poison Json's initialization.
        JsonException failure = assertThrows(JsonException.class, Json::global);
        assertEquals(JsonException.Kind.PROVIDER, failure.kind());
        assertThrows(JsonException.class, Json::defaultMapper);
        JsonMapper custom = mapper();
        AtomicInteger builds = new AtomicInteger();
        Json.registerGlobalFactory(() -> {
            builds.incrementAndGet();
            return custom;
        });
        assertEquals(0, builds.get());
        assertConfiguration(() -> Json.registerGlobalFactory(() -> custom));
        Json.registerGlobal("api-fixture", custom);
        assertSame(custom, Json.global());
        assertSame(custom, Json.defaultMapper());
        assertEquals(1, builds.get());
        assertSame(custom, Json.global("api-fixture"));
        assertConfiguration(() -> Json.registerGlobal(custom));
        assertConfiguration(() -> Json.registerGlobal("api-fixture", custom));
        assertConfiguration(() -> Json.global("unknown"));
    }

    private static void assertConfiguration(Runnable action) {
        JsonException error = assertThrows(JsonException.class, action::run);
        assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static JsonMapper mapper() {
        // A JDK interface proxy is an identity-only fixture; binding is tested with both real engines.
        return (JsonMapper) Proxy.newProxyInstance(JsonMapper.class.getClassLoader(),
                new Class<?>[] {JsonMapper.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected binding on identity fixture: " + method.getName());
                });
    }
}
