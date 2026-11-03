package org.jetlinks.json;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/** Thread-safe, write-once application configuration references, owned by Json's ClassLoader.
 * No active resources, background work or runtime reconfiguration are owned here.
 */
final class GlobalJsonMappers {
    private Supplier<? extends JsonMapper> defaultFactory;
    private final ConcurrentMap<String, JsonMapper> named = new ConcurrentHashMap<>();
    private volatile JsonMapper defaultMapper;
    // Guarded by this; only active resolver threads are retained, never published lookups.
    private final Set<Thread> initializing = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean reserved;

    GlobalJsonMappers(Supplier<JsonMapper> defaultFactory) {
        this.defaultFactory = Objects.requireNonNull(defaultFactory, "defaultFactory");
    }

    JsonMapper mapper() {
        JsonMapper result = defaultMapper;
        return result == null ? initializeDefault() : result;
    }

    JsonMapper mapper(String scope) {
        JsonMapper result = named.get(validateScope(scope));
        if (result == null) {
            throw configuration("JSON configuration is not registered: " + scope);
        }
        return result;
    }

    synchronized void register(JsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        requireUnregistered();
        defaultMapper = mapper;
        defaultFactory = null;
    }

    synchronized void registerFactory(Supplier<? extends JsonMapper> factory) {
        Objects.requireNonNull(factory, "factory");
        requireUnregistered();
        defaultFactory = factory;
        reserved = true;
    }

    private void requireUnregistered() {
        if (defaultMapper != null || !initializing.isEmpty() || reserved) {
            throw configuration("Default JSON mapper is already registered or being initialized");
        }
    }

    void register(String scope, JsonMapper mapper) {
        String name = validateScope(scope);
        Objects.requireNonNull(mapper, "mapper");
        if (named.putIfAbsent(name, mapper) != null) {
            throw configuration("JSON configuration is already registered: " + name);
        }
    }

    private JsonMapper initializeDefault() {
        Thread caller = Thread.currentThread();
        Supplier<? extends JsonMapper> factory;
        synchronized (this) {
            if (defaultMapper != null) {
                return defaultMapper;
            }
            if (!initializing.add(caller)) {
                throw configuration("Recursive default JSON mapper initialization");
            }
            factory = defaultFactory;
        }
        try {
            // A caller may own a DI singleton lock. Never call foreign code or wait for another
            // resolver under our monitor; the container owns construction, we own publication.
            JsonMapper candidate = Objects.requireNonNull(factory.get(), "default mapper");
            synchronized (this) {
                if (defaultMapper == null) {
                    defaultMapper = candidate;
                    // Successful publication must not retain a factory's captured container.
                    defaultFactory = null;
                }
                return defaultMapper;
            }
        } finally {
            synchronized (this) {
                // Failed resolution can retry; recursive failure must not remove an outer owner.
                initializing.remove(caller);
            }
        }
    }

    private static String validateScope(String scope) {
        Objects.requireNonNull(scope, "scope");
        if (scope.isBlank() || !scope.equals(scope.strip())) {
            throw new IllegalArgumentException("JSON configuration name must be nonblank without surrounding whitespace");
        }
        return scope;
    }

    private static JsonException configuration(String message) {
        return new JsonException(JsonException.Kind.CONFIGURATION, message);
    }
}
