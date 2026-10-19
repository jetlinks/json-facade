package org.jetlinks.json.spi;

/** Marker for provider-specific build-time configuration; native callbacks live in provider modules.
 * Extensions must never mutate or publish the native mapper after construction.
 * @since 1.0.0
 */
public interface JsonNativeExtension {
    /** Provider identity this extension requires; mismatches fail before mapper creation. */
    String providerId();
}
