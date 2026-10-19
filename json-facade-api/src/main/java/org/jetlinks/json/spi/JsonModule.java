package org.jetlinks.json.spi;

/** Named, thread-safe collection of neutral adapters. Configured once before a mapper is published.
 * @since 1.0.0
 */
public interface JsonModule {
    /** Return a stable, non-null diagnostic identity, unique within one mapper configuration.
     * Called synchronously at build time without I/O or other side effects.
     * @return module identity used to reject duplicate registration
     * @since 1.0.0
     */
    String id();
    /** Register exact-class adapters synchronously before the mapper is published.
     * Implementations may be reused across builds; do not perform I/O or retain mutable context.
     * @param context non-null build-time registry; never retain or modify it after returning
     * @throws org.jetlinks.json.JsonException on invalid registration; errors propagate without retry
     * @since 1.0.0
     * @see JsonModuleContext#add
     */
    void configure(JsonModuleContext context);
}
