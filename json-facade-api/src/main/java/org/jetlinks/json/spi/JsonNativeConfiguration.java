package org.jetlinks.json.spi;

import java.util.Objects;
import java.util.function.Consumer;

/** Typed build-time customization of a Provider's borrowed native builder.
 * Reusable callbacks must be thread-safe and must not retain the builder or perform I/O.
 * The Provider creates and validates an independent native configuration for each build.
 * @param <C> native builder type, supplied by the selected engine
 * @since 1.0.0
 * @see JsonConfiguration#configureNative(Object)
 */
public record JsonNativeConfiguration<C>(Class<C> type, Consumer<? super C> callback) {
    public JsonNativeConfiguration {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
    }

    void applyTo(Object builder) {
        callback.accept(type.cast(builder));
    }
}
