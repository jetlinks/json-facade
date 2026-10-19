package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonReadSession;

/** Owns one serial parser operation at a time. Cancellation marks the resource closed without
 * waiting; an active owner performs the single cleanup when it releases the operation.
 */
final class ReadSessionResource<T> implements AutoCloseable {
    private static final int IDLE = 0;
    private static final int ACTIVE = 1;
    private static final int CLOSED = 2;
    private final JsonReadSession<T> session;
    private final AtomicInteger state = new AtomicInteger();
    private final BooleanSupplier cancellation = this::cancelled;

    ReadSessionResource(JsonReadSession<T> session) {
        this.session = session;
    }

    void feed(ByteBuffer input) {
        if (acquire()) {
            try {
                session.feed(input);
            } finally {
                release();
            }
        }
    }

    JsonReadSession.Result<T> read() {
        if (!acquire()) {
            return JsonReadSession.Result.end();
        }
        try {
            return session.read(cancellation);
        } finally {
            release();
        }
    }

    void endInput() {
        if (acquire()) {
            try {
                session.endInput();
            } finally {
                release();
            }
        }
    }

    boolean cancelled() {
        return (state.get() & CLOSED) != 0;
    }

    private boolean acquire() {
        if (state.compareAndSet(IDLE, ACTIVE)) {
            return true;
        }
        if (cancelled()) {
            return false;
        }
        throw new IllegalStateException("Concurrent JSON session operation");
    }

    private void release() {
        // Only cancellation can change ACTIVE while the serial owner is running.
        if (!state.compareAndSet(ACTIVE, IDLE)) {
            state.set(CLOSED);
            session.close();
        }
    }

    @Override
    public void close() {
        if (state.getAndUpdate(current -> current | CLOSED) == IDLE) {
            session.close();
        }
    }
}
