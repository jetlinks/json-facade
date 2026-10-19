package org.jetlinks.json.spi;

/** Build-time adapter registry; duplicate registrations fail instead of depending on scan order.
 * @since 1.0.0
 */
public interface JsonModuleContext {
    /** Register a thread-safe adapter for exactly type; neither argument may be null.
     * Called during mapper construction, before concurrent operations become possible.
     * The registration has no I/O and takes effect only for the mapper being built.
     * @param type exact class or interface to bind; subclasses/implementations are not implicitly matched
     * @param adapter thread-safe adapter, called synchronously on the operation thread
     * @param <T> bound type
     * @throws org.jetlinks.json.JsonException on duplicate registration; no previous adapter is replaced
     * @since 1.0.0
     * @see JsonAdapter
     */
    <T> void add(Class<T> type, JsonAdapter<T> adapter);
}
