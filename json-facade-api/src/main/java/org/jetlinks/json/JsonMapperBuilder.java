package org.jetlinks.json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonNativeConfiguration;
import org.jetlinks.json.spi.JsonProvider;

/** Operation-local builder. No mapper is globally changed; multiple engines require explicit choice.
 * @since 1.0.0
 */
public final class JsonMapperBuilder {
    private JsonOptions options = JsonOptions.DEFAULT;
    private final List<JsonModule> modules = new ArrayList<>();
    private final List<JsonNativeConfiguration<?>> nativeConfigurations = new ArrayList<>();
    private JsonProvider provider;
    private String providerId;
    private ClassLoader classLoader = Json.class.getClassLoader();

    public JsonMapperBuilder options(JsonOptions value) {
        options = Objects.requireNonNull(value, "options");
        return this;
    }

    public JsonMapperBuilder module(JsonModule value) {
        modules.add(Objects.requireNonNull(value, "module"));
        return this;
    }

    /** Configure the selected engine's native builder during each build, in registration order.
     * All native types are checked before any callback runs. Shared policies remain governed by
     * JsonOptions; conflicting native settings fail at build time. Never retain the borrowed builder.
     * @param type native builder type supplied by the chosen Provider
     * @param configure synchronous build-time callback; reusable callbacks must be thread-safe
     * @param <C> native builder type
     * @return this operation-local builder
     * @since 1.0.0
     * @see JsonConfiguration#configureNative(Object)
     */
    public <C> JsonMapperBuilder configureNative(Class<C> type, Consumer<? super C> configure) {
        nativeConfigurations.add(new JsonNativeConfiguration<>(type, configure));
        return this;
    }

    public JsonMapperBuilder provider(JsonProvider value) {
        provider = Objects.requireNonNull(value, "provider");
        providerId = null;
        return this;
    }

    public JsonMapperBuilder provider(String id) {
        providerId = Objects.requireNonNull(id, "providerId");
        provider = null;
        return this;
    }

    public JsonMapperBuilder classLoader(ClassLoader value) {
        classLoader = Objects.requireNonNull(value, "classLoader");
        return this;
    }

    public JsonMapper build() {
        JsonProvider selected = provider == null ? discover() : provider;
        Set<String> ids = new HashSet<>();
        for (JsonModule module : modules) {
            if (!ids.add(module.id())) {
                throw new JsonException(JsonException.Kind.CONFIGURATION, "Duplicate JSON module: " + module.id());
            }
        }
        return selected.create(new JsonConfiguration(options, modules, nativeConfigurations));
    }

    private JsonProvider discover() {
        List<JsonProvider> candidates = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonProvider candidate : ServiceLoader.load(JsonProvider.class, classLoader)) {
            if (!ids.add(candidate.id())) {
                throw new JsonException(JsonException.Kind.PROVIDER, "Duplicate JSON provider: " + candidate.id());
            }
            if (providerId == null || providerId.equals(candidate.id())) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() != 1) {
            throw new JsonException(JsonException.Kind.PROVIDER,
                    "Select exactly one JSON provider; candidates=" + candidates.size());
        }
        return candidates.get(0);
    }
}
