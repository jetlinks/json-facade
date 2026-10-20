package org.jetlinks.json.spi;

import java.util.List;
import java.util.Objects;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonOptions;

/** Immutable construction input shared by providers. Lists are defensively copied.
 * Modules/native callbacks must themselves be thread-safe and build-only.
 * @since 1.0.0
 */
public record JsonConfiguration(JsonOptions options, List<JsonModule> modules,
                                List<JsonNativeConfiguration<?>> nativeConfigurations) {
    public JsonConfiguration {
        Objects.requireNonNull(options, "options");
        modules = List.copyOf(modules);
        nativeConfigurations = List.copyOf(nativeConfigurations);
    }

    /** Apply ordered native callbacks on the building thread, after checking every target type.
     * Providers call this once per build with a fresh, unpublished native builder, then validate
     * shared policies and ownership constraints before publishing their mapper.
     * @param builder non-null operation-local native builder, borrowed only by each callback
     * @throws JsonException CONFIGURATION on a type mismatch or callback failure, without retry
     * @since 1.0.0
     * @see org.jetlinks.json.JsonMapperBuilder#configureNative(Class, java.util.function.Consumer)
     */
    public void configureNative(Object builder) {
        Objects.requireNonNull(builder, "builder");
        // Reject an incompatible batch before any callback can change its native builder.
        for (JsonNativeConfiguration<?> configuration : nativeConfigurations) {
            if (!configuration.type().isInstance(builder)) {
                throw new JsonException(JsonException.Kind.CONFIGURATION,
                        "Native JSON configuration requires " + configuration.type().getName()
                                + "; selected Provider supplies " + builder.getClass().getName());
            }
        }
        for (JsonNativeConfiguration<?> configuration : nativeConfigurations) {
            try {
                configuration.applyTo(builder);
            } catch (RuntimeException error) {
                throw new JsonException(JsonException.Kind.CONFIGURATION,
                        "Native JSON configuration callback failed", error);
            }
        }
    }
}
