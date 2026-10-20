package org.jetlinks.json.reactor.spi;

import java.nio.ByteBuffer;

/** A transferred input lease, consumed serially by one JSON stream subscription.
 * Views must remain stable until close; parsing and binding remain with the Provider.
 * The publisher owns leases it has not delivered, including its own queued discards.
 * @since 1.0.0
 * @see org.jetlinks.json.reactor.JsonStreamMapper#readChunks
 */
public interface JsonInputChunk extends AutoCloseable {
    /** Return the next borrowed readable region, or null when all regions have been supplied.
     * Called on the parsing thread only after the previous region has been consumed.
     * Empty regions are permitted. Do not block or mutate previously returned storage.
     * Failures terminate the stream and close this lease.
     */
    ByteBuffer nextBuffer();

    /** Release the lease after consumption or parser cleanup on failure/cancellation.
     * Must be idempotent and release every owned reference, without closing the input source.
     * Cancellation during a callback defers this call until that callback returns.
     */
    @Override
    void close();
}
