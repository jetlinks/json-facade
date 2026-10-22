package org.jetlinks.json;

import java.util.Iterator;

/** Synchronous, operation-local JSON iteration with explicit parser cleanup.
 * Not thread-safe or reentrant. Values may be Java null; hasNext, not a null value, reports exhaustion.
 * Use try-with-resources even when iteration may stop early or consumer code may throw.
 * Closing releases parser state without closing the caller-owned source. Fully consuming an
 * array validates its closing bracket and trailing input; early close validates only its prefix.
 * @param <T> bound element type
 * @since 1.0.0
 * @see JsonMapper#iterateArray(java.io.InputStream, JsonType)
 */
public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {
    /** Idempotently release parser state; no further values are available after close.
     * The caller retains source ownership. Cleanup failures propagate as unchecked exceptions.
     */
    @Override
    void close();
}
