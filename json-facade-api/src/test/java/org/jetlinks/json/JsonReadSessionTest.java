package org.jetlinks.json;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonReadSessionTest {
    @Test
    void existingSessionsBridgeValuesNullAndControlStatesToAContravariantReceiver() {
        LegacySession session = new LegacySession();
        JsonReadSession.Receiver<Number, JsonReadSession.Result<Number>> receiver = JsonReadSession.Result::new;
        for (JsonReadSession.Result<Integer> result : List.of(JsonReadSession.Result.value(1),
                JsonReadSession.Result.<Integer>value(null), JsonReadSession.Result.<Integer>needInput(),
                JsonReadSession.Result.<Integer>end())) {
            session.result = result;
            assertEquals(result, session.read(receiver, () -> false));
        }
        assertEquals(4, session.reads);
        assertNull(session.read((state, value) -> null, () -> false));
    }

    @Test
    void bridgeHonorsCancellationBeforeCallingAnExistingSession() {
        LegacySession session = new LegacySession();
        assertEquals(JsonReadSession.State.END, session.read((state, value) -> {
            assertNull(value);
            return state;
        }, () -> true));
        assertEquals(0, session.reads);
    }

    @Test
    void bridgeValidatesArgumentsAndPreservesReceiverFailure() {
        LegacySession session = new LegacySession();
        RuntimeException failure = new RuntimeException("receiver");
        assertSame(failure, assertThrows(RuntimeException.class, () -> session.read((state, value) -> {
            throw failure;
        }, () -> false)));
        assertThrows(NullPointerException.class, () -> session.read(null, () -> false));
        assertThrows(NullPointerException.class, () -> session.read((state, value) -> state, null));
        assertEquals(1, session.reads);
    }

    /** Represents an independent implementation of the original pull-only session contract. */
    private static final class LegacySession implements JsonReadSession<Integer> {
        private Result<Integer> result = Result.value(1);
        private int reads;

        @Override
        public boolean needsInput() {
            return true;
        }

        @Override
        public void feed(ByteBuffer input) {
        }

        @Override
        public Result<Integer> read() {
            reads++;
            return result;
        }

        @Override
        public void endInput() {
        }

        @Override
        public void close() {
        }
    }
}
