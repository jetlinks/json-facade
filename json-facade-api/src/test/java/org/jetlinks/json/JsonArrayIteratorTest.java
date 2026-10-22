package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonArrayIteratorTest {
    private static final InputStream INPUT = new ByteArrayInputStream(new byte[0]);

    @Test
    void creationValidatesArgumentsAndCloseBeforeUseNeverOpensACursor() {
        CursorMapper mapper = new CursorMapper(JsonToken.START_ARRAY, JsonToken.NUMBER, JsonToken.END_ARRAY);
        assertThrows(NullPointerException.class, () -> mapper.iterateArray(null, Integer.class));
        assertThrows(NullPointerException.class, () -> mapper.iterateArray(INPUT, (Class<Integer>) null));
        assertThrows(NullPointerException.class, () -> mapper.iterateArray(INPUT, (JsonType<Integer>) null));
        JsonType<Integer> indirect = new IntegerType() { };
        assertThrows(IllegalArgumentException.class, () -> mapper.iterateArray(INPUT, indirect));
        CloseableIterator<Integer> iterator = mapper.iterateArray(INPUT, Integer.class);
        iterator.close();
        iterator.close();
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertEquals(0, mapper.opens);
        assertEquals(0, mapper.cursor.closes);
    }

    @Test
    void lookaheadDoesNotBindOrAdvanceTwiceAndExhaustionClosesOnce() {
        CursorMapper mapper = new CursorMapper(JsonToken.START_ARRAY, JsonToken.NULL, JsonToken.END_ARRAY);
        CloseableIterator<Integer> iterator = mapper.iterateArray(INPUT, JsonType.of(Integer.class));
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertEquals(2, mapper.cursor.advances);
        assertEquals(0, mapper.cursor.binds);
        assertNull(iterator.next());
        assertEquals(1, mapper.cursor.binds);
        assertFalse(iterator.hasNext());
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        iterator.close();
        assertEquals(1, mapper.opens);
        assertEquals(1, mapper.cursor.closes);
    }

    @Test
    void framingFailuresCloseTheCursorAndLeaveNoNextValue() {
        for (JsonToken[] tokens : new JsonToken[][] {
                {}, {JsonToken.START_OBJECT}, {JsonToken.START_ARRAY},
                {JsonToken.START_ARRAY, JsonToken.END_ARRAY, JsonToken.NUMBER}}) {
            CursorMapper mapper = new CursorMapper(tokens);
            CloseableIterator<Integer> iterator = mapper.iterateArray(INPUT, Integer.class);
            assertEquals(JsonException.Kind.SYNTAX, assertThrows(JsonException.class, iterator::hasNext).kind());
            assertFalse(iterator.hasNext());
            assertEquals(1, mapper.cursor.closes);
        }
    }

    @Test
    void bindingFailureRetainsIdentityAndSuppressesCleanupFailure() {
        CursorMapper mapper = new CursorMapper(JsonToken.START_ARRAY, JsonToken.NUMBER);
        RuntimeException failure = new IllegalArgumentException("binding");
        RuntimeException cleanup = new IllegalStateException("cleanup");
        mapper.cursor.bindingFailure = failure;
        mapper.cursor.closeFailure = cleanup;
        CloseableIterator<Integer> iterator = mapper.iterateArray(INPUT, Integer.class);
        assertSame(failure, assertThrows(RuntimeException.class, iterator::next));
        assertArrayEquals(new Throwable[] {cleanup}, failure.getSuppressed());
        iterator.close();
        assertFalse(iterator.hasNext());
        assertEquals(1, mapper.cursor.closes);
    }

    @Test
    void cleanupFailureIsPropagatedOnceAndSelfSuppressionIsAvoided() {
        CursorMapper mapper = new CursorMapper(JsonToken.START_ARRAY, JsonToken.NUMBER);
        RuntimeException failure = new IllegalStateException("same failure");
        mapper.cursor.nextFailure = failure;
        mapper.cursor.closeFailure = failure;
        CloseableIterator<Integer> iterator = mapper.iterateArray(INPUT, Integer.class);
        assertSame(failure, assertThrows(RuntimeException.class, iterator::hasNext));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, mapper.cursor.closes);
        iterator.close();

        CursorMapper closing = new CursorMapper(JsonToken.START_ARRAY, JsonToken.END_ARRAY);
        closing.cursor.closeFailure = failure;
        CloseableIterator<Integer> empty = closing.iterateArray(INPUT, Integer.class);
        assertSame(failure, assertThrows(RuntimeException.class, empty::hasNext));
        assertFalse(empty.hasNext());
        empty.close();
        assertEquals(1, closing.cursor.closes);
    }

    @Test
    void cursorCreationAndFatalFailuresStillTerminateTheOperation() {
        CursorMapper opening = new CursorMapper();
        opening.openFailure = new IllegalStateException("open");
        CloseableIterator<Integer> unopened = opening.iterateArray(INPUT, Integer.class);
        assertSame(opening.openFailure, assertThrows(RuntimeException.class, unopened::hasNext));
        assertFalse(unopened.hasNext());
        assertEquals(0, opening.cursor.closes);

        CursorMapper fatal = new CursorMapper(JsonToken.START_ARRAY, JsonToken.NUMBER);
        fatal.cursor.fatal = new AssertionError("fatal");
        CloseableIterator<Integer> iterator = fatal.iterateArray(INPUT, Integer.class);
        assertSame(fatal.cursor.fatal, assertThrows(AssertionError.class, iterator::next));
        assertFalse(iterator.hasNext());
        assertEquals(1, fatal.cursor.closes);
    }

    private static class IntegerType extends JsonType<Integer> {
    }

    /** A foreign cursor exposes cleanup failures that actual engines cannot reliably induce. */
    private static final class Cursor implements JsonReader {
        private final JsonToken[] tokens;
        private int advances;
        private int binds;
        private int closes;
        private RuntimeException nextFailure;
        private RuntimeException bindingFailure;
        private RuntimeException closeFailure;
        private AssertionError fatal;

        private Cursor(JsonToken[] tokens) {
            this.tokens = tokens;
        }

        @Override
        public JsonToken next() {
            if (nextFailure != null) {
                throw nextFailure;
            }
            int index = advances++;
            return index < tokens.length ? tokens[index] : null;
        }

        @Override
        public JsonToken current() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String text() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T readValue(JsonType<T> type) {
            binds++;
            if (fatal != null) {
                throw fatal;
            }
            if (bindingFailure != null) {
                throw bindingFailure;
            }
            return null;
        }

        @Override
        public void skipValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closes++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    /** Only cursor creation is supported; the iterator must not call whole-value or session APIs. */
    private static final class CursorMapper implements JsonMapper {
        private final Cursor cursor;
        private int opens;
        private RuntimeException openFailure;

        private CursorMapper(JsonToken... tokens) {
            cursor = new Cursor(tokens);
        }

        @Override
        public JsonReader reader(InputStream input) {
            opens++;
            if (openFailure != null) {
                throw openFailure;
            }
            return cursor;
        }

        @Override
        public JsonOptions options() { throw new UnsupportedOperationException(); }
        @Override
        public void write(Object value, OutputStream output) { throw new UnsupportedOperationException(); }
        @Override
        public void write(Object value, Writer output) { throw new UnsupportedOperationException(); }
        @Override
        public void writeCompact(Object value, OutputStream output) { throw new UnsupportedOperationException(); }
        @Override
        public <T> T read(InputStream input, JsonType<T> type) { throw new UnsupportedOperationException(); }
        @Override
        public <T> T read(Reader input, JsonType<T> type) { throw new UnsupportedOperationException(); }
        @Override
        public <T> T convert(Object value, JsonType<T> type) { throw new UnsupportedOperationException(); }
        @Override
        public JsonWriter writer(OutputStream output) { throw new UnsupportedOperationException(); }
        @Override
        public JsonWriter compactWriter(OutputStream output) { throw new UnsupportedOperationException(); }
        @Override
        public JsonReadSession<JsonEvent> eventSession() { throw new UnsupportedOperationException(); }
        @Override
        public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions options) {
            throw new UnsupportedOperationException();
        }
    }
}
