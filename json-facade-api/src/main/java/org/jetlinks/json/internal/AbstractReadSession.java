package org.jetlinks.json.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;

/** Shared framing/selection state machine. Providers supply incremental tokens, never whole documents.
 * Only the active selected record is materialized; this class owns no threads or source buffers.
 */
public abstract class AbstractReadSession<T> implements JsonReadSession<T> {
    /** null token indicates a parser input boundary or genuine EOF, distinguished by exhausted. */
    protected record Event(JsonToken token, Object value, long start, long end, long line, long endLine) {
        public Event {
        }
    }

    private final JsonMapper mapper;
    private final JsonType<T> type;
    private final JsonReadOptions options;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private ValueBuilder record;
    private long recordStart;
    private long recordLine;
    private long lastRecordLine;
    private long previousRootEnd = -1;
    private int roots;
    private boolean selected;
    private boolean closed;
    private boolean inputEnded;
    private boolean pendingCarriageReturn;

    protected AbstractReadSession(JsonMapper mapper, JsonType<T> type, JsonReadOptions options) {
        this.mapper = mapper;
        this.type = type;
        this.options = options;
    }

    protected abstract Event nextEvent();
    protected abstract boolean exhausted();
    protected abstract boolean parserNeedsInput();
    protected abstract void feedParser(ByteBuffer input);
    protected abstract void finishParser();
    protected abstract void closeParser();

    @Override
    public final boolean needsInput() {
        return !closed && !inputEnded && parserNeedsInput();
    }

    @Override
    public final void feed(ByteBuffer input) {
        if (!needsInput()) {
            throw new IllegalStateException("Parser is not ready for input");
        }
        if (input.hasRemaining()) {
            validateLineEndings(input.asReadOnlyBuffer());
            feedParser(input.asReadOnlyBuffer());
        }
    }

    private void validateLineEndings(ByteBuffer input) {
        if (options.mode() != JsonReadOptions.Mode.NDJSON) {
            return;
        }
        // Jackson accepts bare CR as a line break; NDJSON only permits LF or CRLF.
        // Preserve this one-byte state across chunks without retaining their memory.
        while (input.hasRemaining()) {
            byte value = input.get();
            if (pendingCarriageReturn && value != '\n') {
                throw error("NDJSON requires LF or CRLF line endings");
            }
            pendingCarriageReturn = value == '\r';
        }
    }

    @Override
    public final void endInput() {
        ensureOpen();
        if (inputEnded) {
            throw new IllegalStateException("Input already ended");
        }
        if (pendingCarriageReturn) {
            throw error("Incomplete CRLF line ending");
        }
        inputEnded = true;
        finishParser();
    }

    @Override
    public final Result<T> read() {
        ensureOpen();
        while (true) {
            Event event = nextEvent();
            if (event.token() == null) {
                return exhausted() ? finishDocument() : Result.needInput();
            }
            List<String> path = advancePath(event);
            validateRoot(event, path);
            if (shouldStart(event, path)) {
                record = new ValueBuilder();
                recordStart = event.start();
                recordLine = event.line();
            }
            if (record != null) {
                checkSize(event);
                record.accept(event.token(), event.value());
                if (record.complete()) {
                    return finishRecord(event);
                }
            }
        }
    }

    private boolean shouldStart(Event event, List<String> path) {
        if (record != null || event.token() == JsonToken.FIELD_NAME || isEnd(event.token())) {
            return false;
        }
        JsonReadOptions.Selection selection = options.selection();
        List<String> target = selection == null ? List.of() : selection.pointer().segments();
        boolean elements = selection == null
                ? options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS : selection.arrayElements();
        if (path.equals(target)) {
            if (selected) {
                throw error("Selected JSON path occurs more than once");
            }
            selected = true;
            if (elements && event.token() != JsonToken.START_ARRAY) {
                throw error("Selected value must be an array");
            }
            return !elements;
        }
        return elements && path.size() == target.size() + 1 && path.subList(0, target.size()).equals(target);
    }

    private Result<T> finishRecord(Event event) {
        if (options.mode() == JsonReadOptions.Mode.NDJSON) {
            if (recordLine != event.endLine() || recordLine == lastRecordLine) {
                throw error("NDJSON requires one complete value per line");
            }
            lastRecordLine = recordLine;
        }
        Object value = record.value();
        record = null;
        if (type.type() == JsonValue.class) {
            return Result.value(castTree(value));
        }
        return Result.value(mapper.convert(value, type));
    }

    @SuppressWarnings("unchecked")
    private T castTree(Object value) {
        // The requested type is exactly JsonValue, checked before this cast.
        return (T) JsonValue.of(value);
    }

    private void checkSize(Event event) {
        long size = event.end() - recordStart + 1;
        int limit = Math.min(options.maxValueBytes(), mapper.options().maxValueBytes());
        if (size > limit) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON record exceeds byte budget");
        }
    }

    private Result<T> finishDocument() {
        if (record != null || !frames.isEmpty()) {
            throw error("Incomplete JSON document");
        }
        boolean requiresDocument = options.mode() == JsonReadOptions.Mode.SINGLE_VALUE
                || options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS;
        if (requiresDocument && (roots == 0 || !selected)) {
            throw error("Empty document or selected JSON path missing");
        }
        return Result.end();
    }

    private void validateRoot(Event event, List<String> path) {
        if (event.token() == JsonToken.FIELD_NAME) {
            return;
        }
        if (path.isEmpty()) {
            if (!isEnd(event.token())) {
                roots++;
                if (roots > 1 && (options.mode() == JsonReadOptions.Mode.SINGLE_VALUE
                        || options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS)) {
                    throw error("Trailing root value");
                }
                if (roots > 1 && event.start() <= previousRootEnd + 1) {
                    throw error("Root values must be separated by whitespace");
                }
                if (options.selection() == null
                        && (options.mode() == JsonReadOptions.Mode.NDJSON
                        || options.mode() == JsonReadOptions.Mode.ROOT_VALUES)) {
                    selected = false;
                }
            }
            if (isEnd(event.token()) || !isStart(event.token())) {
                previousRootEnd = event.end();
            }
        }
    }

    private List<String> advancePath(Event event) {
        JsonToken token = event.token();
        if (token == JsonToken.FIELD_NAME) {
            frames.peek().field = (String) event.value();
            return frames.peek().path;
        }
        if (isEnd(token)) {
            return frames.pop().path;
        }
        List<String> path = new ArrayList<>();
        if (!frames.isEmpty()) {
            Frame parent = frames.peek();
            path.addAll(parent.path);
            path.add(parent.array ? Integer.toString(parent.index++) : parent.field);
        }
        if (isStart(token)) {
            frames.push(new Frame(path, token == JsonToken.START_ARRAY));
        }
        return path;
    }

    private static boolean isStart(JsonToken token) {
        return token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY;
    }

    private static boolean isEnd(JsonToken token) {
        return token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY;
    }

    private static JsonException error(String message) {
        return new JsonException(JsonException.Kind.SYNTAX, message);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("JSON session is closed");
        }
    }

    @Override
    public final void close() {
        if (!closed) {
            closed = true;
            record = null;
            frames.clear();
            closeParser();
        }
    }

    private static final class Frame {
        private final List<String> path;
        private final boolean array;
        private String field;
        private int index;

        private Frame(List<String> path, boolean array) {
            this.path = List.copyOf(path);
            this.array = array;
        }
    }
}
