package org.jetlinks.json.reactor;

import org.jetlinks.json.reactor.spi.JsonBufferAllocator;

/** Keeps discard ownership distinct from user values travelling through concatMap.
 * A segment transfers once at the public output boundary, or releases on internal discard.
 */
final class OutputSegment<B> {
    private final JsonBufferAllocator<B> allocator;
    private B buffer;

    OutputSegment(B buffer, JsonBufferAllocator<B> allocator) {
        this.buffer = buffer;
        this.allocator = allocator;
    }

    synchronized B take() {
        B owned = buffer;
        buffer = null;
        return owned;
    }

    synchronized void close() {
        B owned = take();
        if (owned != null) {
            allocator.release(owned);
        }
    }
}
