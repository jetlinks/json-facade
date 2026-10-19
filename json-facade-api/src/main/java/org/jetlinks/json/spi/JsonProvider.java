package org.jetlinks.json.spi;

import org.jetlinks.json.JsonMapper;

/** Minimal engine SPI, discovered with ServiceLoader or supplied explicitly.
 * No Spring, Reactor, business types or global mutable mapper registration.
 * @since 1.0.0
 */
public interface JsonProvider {
    /** Return a stable, non-null, unique identity such as jackson2, with no side effects.
     * Called synchronously during selection; must be safe for concurrent mapper builds.
     * @return provider identity, never dependent on classpath discovery order
     * @since 1.0.0
     */
    String id();
    /** Build a fully configured, immutable and thread-safe mapper.
     * Must honor all declared facade contracts, including independent incremental sessions.
     * Unsupported/mismatched configuration fails now; never silently ignore it or fall back.
     * Called synchronously by the builder; do not retain mutable construction state or perform I/O.
     * @param configuration non-null immutable options and ordered extensions; do not mutate their members
     * @return a non-null, independently configured mapper, safe for concurrent operations
     * @throws org.jetlinks.json.JsonException if construction or configuration is unsupported
     * @since 1.0.0
     * @see JsonMapper#readSession
     */
    JsonMapper create(JsonConfiguration configuration);
}
