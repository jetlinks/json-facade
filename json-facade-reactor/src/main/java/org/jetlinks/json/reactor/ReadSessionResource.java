package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/** Owns one serial parser operation at a time. Cancellation marks the resource closed without
 * waiting; an active owner performs the single cleanup when it releases the operation.
 */
final class ReadSessionResource<T> extends OperationResource
        implements JsonReadSession.Receiver<T, JsonReadSession.State> {
    private JsonReadSession<T> session;
    private JsonInputChunk chunk;
    private T value;
    private final BooleanSupplier cancellation = this::cancelled;
    private final JsonReadOptions.NullPolicy nullPolicy;
    // concatMap serializes drain subscriptions. Reuse the publisher, never its subscription/demand.
    private final Flux<T> drain = Flux.generate(this::emit);

    ReadSessionResource(JsonReadSession<T> session, JsonReadOptions.NullPolicy nullPolicy) {
        this.session = session;
        this.nullPolicy = nullPolicy;
    }

    Flux<T> decode(ByteBuffer input) {
        feed(input);
        return drain;
    }

    Flux<T> decodeChunk(JsonInputChunk input) {
        if (acquire()) {
            try {
                chunk = input;
                feedNextBuffer();
            } finally {
                release();
            }
        } else {
            input.close();
        }
        return drain;
    }

    private boolean feedNextBuffer() {
        if (chunk == null) {
            return false;
        }
        ByteBuffer buffer;
        while ((buffer = chunk.nextBuffer()) != null) {
            if (buffer.hasRemaining()) {
                session.feed(buffer);
                return true;
            }
        }
        closeChunk();
        return false;
    }

    private void closeChunk() {
        JsonInputChunk owned = chunk;
        chunk = null;
        if (owned != null) {
            owned.close();
        }
    }

    Flux<T> finish() {
        endInput();
        return drain;
    }

    private void emit(SynchronousSink<T> sink) {
        while (!cancelled()) {
            if (!acquire()) {
                break;
            }
            JsonReadSession.State state;
            T next;
            try {
                state = readOwned();
                next = value;
            } finally {
                value = null;
                release();
            }
            // Downstream can synchronously cancel/reenter; native ownership has already ended.
            if (cancelled() || state != JsonReadSession.State.VALUE) {
                sink.complete();
                return;
            }
            if (next != null) {
                sink.next(next);
                return;
            }
            if (nullPolicy == JsonReadOptions.NullPolicy.FAIL) {
                sink.error(new JsonException(JsonException.Kind.BINDING, "Reactor cannot emit a Java null record"));
                return;
            }
        }
        sink.complete();
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

    private JsonReadSession.State readOwned() {
        JsonReadSession.State state;
        do {
            state = session.read(this, cancellation);
        } while (!cancelled() && state == JsonReadSession.State.NEED_INPUT && feedNextBuffer());
        return state;
    }

    @Override
    public JsonReadSession.State receive(JsonReadSession.State state, T next) {
        value = next;
        return state;
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

    @Override
    protected void closeOwned() {
        JsonReadSession<T> owned = session;
        session = null;
        value = null;
        // The native parser may still access its borrowed view during close.
        try {
            owned.close();
        } finally {
            closeChunk();
        }
    }
}
