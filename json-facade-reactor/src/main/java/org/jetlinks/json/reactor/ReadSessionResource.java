package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonReadSession;

/** Owns one serial parser operation at a time. Cancellation marks the resource closed without
 * waiting; an active owner performs the single cleanup when it releases the operation.
 */
final class ReadSessionResource<T> extends OperationResource {
    private final JsonReadSession<T> session;
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

    @Override
    protected void closeOwned() {
        session.close();
    }
}
