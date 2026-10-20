package org.jetlinks.json;

import java.util.Objects;
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
    private boolean initializing;
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
        if (defaultMapper != null || initializing || reserved) {
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

    private synchronized JsonMapper initializeDefault() {
        if (defaultMapper == null) {
            // Serialize first publication with registration. The volatile hit path never takes this lock.
            if (initializing) {
                throw configuration("Recursive default JSON mapper initialization");
            }
            initializing = true;
            try {
                defaultMapper = Objects.requireNonNull(defaultFactory.get(), "default mapper");
                // In particular, do not keep a DI container captured by a successful factory.
                defaultFactory = null;
            } finally {
                // Failed construction is not a class-initialization failure or a published instance.
                initializing = false;
            }
        }
        return defaultMapper;
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
