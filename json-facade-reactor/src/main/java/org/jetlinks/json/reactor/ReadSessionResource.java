package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/** Owns one serial parser operation at a time. Cancellation marks the resource closed without
 * waiting; an active owner performs the single cleanup when it releases the operation.
 */
final class ReadSessionResource<T> extends OperationResource {
    private JsonReadSession<T> session;
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

    Flux<T> finish() {
        endInput();
        return drain;
    }

    private void emit(SynchronousSink<T> sink) {
        while (!cancelled()) {
            JsonReadSession.Result<T> result = read();
            if (result.state() != JsonReadSession.State.VALUE) {
                sink.complete();
                return;
            }
            if (result.value() != null) {
                sink.next(result.value());
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

    @Override
    protected void closeOwned() {
        JsonReadSession<T> owned = session;
        session = null;
        owned.close();
    }
}
