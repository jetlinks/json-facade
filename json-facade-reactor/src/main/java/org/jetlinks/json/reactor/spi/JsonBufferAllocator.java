package org.jetlinks.json.reactor.spi;

/** Transport storage for bounded JSON output segments. Shared allocator implementations must
 * be thread-safe; each allocated buffer is used serially by one subscription until transfer.
 * Methods are synchronous memory operations, never blocking I/O. Failures terminate output.
 * The Reactor adapter owns framing, budgets and cleanup; the Provider owns JSON encoding.
 * @since 1.0.0
 * @see org.jetlinks.json.reactor.JsonStreamMapper#writeChunks
 * @param <B> transport buffer type
 */
public interface JsonBufferAllocator<B> {
    /** Allocate independently owned storage of at least capacity bytes, never null.
     * Bytes beyond the requested capacity are not used. Failure must leave no owned allocation.
     */
    B allocate(int capacity);

    /** Write one byte at the supplied zero-based offset, within requested capacity. */
    void writeByte(B buffer, int offset, int value);

    /** Copy the given source region into the buffer at offset. Do not retain the source array.
     * Both regions have been checked by the adapter; implementations preserve preceding bytes.
     */
    void writeBytes(B buffer, int offset, byte[] source, int sourceOffset, int length);

    /** Expose exactly length readable bytes and transfer ownership to the returned buffer.
     * May return the same instance. On failure the original remains owned and releasable.
     * A replacement must release any storage it no longer needs.
     */
    B seal(B buffer, int length);

    /** Release one unpublished owned buffer. Called once per allocation or successful seal.
     * Must tolerate valid empty buffers; it need not tolerate a second release of the same reference.
     * Emitted buffers belong to downstream, including downstream queues and discards.
     */
    void release(B buffer);
}
