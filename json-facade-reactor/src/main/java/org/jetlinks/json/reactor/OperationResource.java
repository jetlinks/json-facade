package org.jetlinks.json.reactor;

import java.util.concurrent.atomic.AtomicInteger;

/** Serial operation ownership. Cancellation never waits for a native/user callback to return. */
abstract class OperationResource implements AutoCloseable {
    private static final int IDLE = 0;
    private static final int ACTIVE = 1;
    private static final int CLOSED = 2;
    private final AtomicInteger state = new AtomicInteger();

    protected final boolean acquire() {
        if (state.compareAndSet(IDLE, ACTIVE)) {
            return true;
        }
        if (cancelled()) {
            return false;
        }
        throw new IllegalStateException("Concurrent JSON session operation");
    }

    protected final void release() {
        // Only cancellation can change ACTIVE while the serial operation owner is running.
        if (!state.compareAndSet(ACTIVE, IDLE)) {
            state.set(CLOSED);
            closeOwned();
        }
    }

    final boolean cancelled() {
        return (state.get() & CLOSED) != 0;
    }

    protected abstract void closeOwned();

    @Override
    public final void close() {
        if (state.getAndUpdate(current -> current | CLOSED) == IDLE) {
            closeOwned();
        }
    }
}
