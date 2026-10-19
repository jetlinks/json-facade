package org.jetlinks.json.spi;

import java.util.List;
import java.util.Objects;
import org.jetlinks.json.JsonOptions;

/** Immutable construction input shared by providers. Lists are defensively copied.
 * Modules/extensions must themselves be thread-safe and build-only.
 * @since 1.0.0
 */
public record JsonConfiguration(JsonOptions options, List<JsonModule> modules,
                                List<JsonNativeExtension> extensions) {
    public JsonConfiguration {
        Objects.requireNonNull(options, "options");
        modules = List.copyOf(modules);
        extensions = List.copyOf(extensions);
    }
}
