package org.jetlinks.json.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonToken;

/** Shared framing/selection state machine. Providers supply incremental tokens, never whole documents.
 * Providers buffer and bind the current record's native tokens; no neutral tree round trip is needed.
 */
public abstract class AbstractReadSession<T> implements JsonReadSession<T> {
    /** null token indicates a parser input boundary or genuine EOF, distinguished by exhausted. */
    protected record Event(JsonToken token, String name, long start, long end, long line, long endLine) {
        public Event {
        }
    }

    private final JsonReadOptions options;
    private final int maxValueBytes;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private boolean recording;
    private int depth;
    private int recordDepth;
    private long recordStart;
    private long recordLine;
    private long lastRecordLine;
    private long previousRootEnd = -1;
    private int roots;
    private boolean selected;
    private boolean closed;
    private boolean inputEnded;
    private boolean pendingCarriageReturn;

    protected AbstractReadSession(JsonReadOptions options, int maxValueBytes) {
        this.options = options;
        this.maxValueBytes = Math.min(options.maxValueBytes(), maxValueBytes);
    }

    protected abstract Event nextEvent();
    protected abstract boolean exhausted();
    protected abstract boolean parserNeedsInput();
    protected abstract void feedParser(ByteBuffer input);
    protected abstract void finishParser();
    protected abstract void closeParser();
    /** Begin buffering the record at the engine's current token. */
    protected abstract void startRecord();
    /** Append the engine's current token without applying serialization policies. */
    protected abstract void appendToken();
    /** Bind and release the completed native record with the configured engine reader. */
    protected abstract T bindRecord();

    /** Field names are needed only while resolving a configured pointer. */
    protected final boolean tracksPath() {
        return options.selection() != null;
    }

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
        return read(() -> false);
    }

    @Override
    public final Result<T> read(BooleanSupplier cancelled) {
        ensureOpen();
        while (!cancelled.getAsBoolean()) {
            Event event = nextEvent();
            if (event.token() == null) {
                return exhausted() ? finishDocument() : Result.needInput();
            }
            int tokenDepth = advanceDepth(event.token());
            // Ordinary root/array reads need only depth; paths would allocate once per input value.
            List<String> path = tracksPath() ? advancePath(event) : List.of();
            validateRoot(event, tokenDepth);
            if (shouldStart(event, path, tokenDepth)) {
                startRecord();
                recording = true;
                recordDepth = tokenDepth;
                recordStart = event.start();
                recordLine = event.line();
            }
            if (recording) {
                checkSize(event);
                appendToken();
                if (!isStart(event.token()) && depth == recordDepth) {
                    return finishRecord(event);
                }
            }
        }
        return Result.end();
    }

    private int advanceDepth(JsonToken token) {
        if (isEnd(token)) {
            depth--;
        }
        int tokenDepth = depth;
        if (isStart(token)) {
            depth++;
        }
        return tokenDepth;
    }

    private boolean shouldStart(Event event, List<String> path, int tokenDepth) {
        if (recording || event.token() == JsonToken.FIELD_NAME || isEnd(event.token())) {
            return false;
        }
        JsonReadOptions.Selection selection = options.selection();
        List<String> target = selection == null ? List.of() : selection.pointer().segments();
        boolean elements = selection == null
                ? options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS : selection.arrayElements();
        if (selection == null ? tokenDepth == 0 : path.equals(target)) {
            if (selected) {
                throw error("Selected JSON path occurs more than once");
            }
            selected = true;
            if (elements && event.token() != JsonToken.START_ARRAY) {
                throw error("Selected value must be an array");
            }
            return !elements;
        }
        if (selection == null) {
            return elements && tokenDepth == 1;
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
        recording = false;
        return Result.value(bindRecord());
    }

    private void checkSize(Event event) {
        long size = event.end() - recordStart + 1;
        if (size > maxValueBytes) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON record exceeds byte budget");
        }
    }

    private Result<T> finishDocument() {
        if (recording || depth != 0) {
            throw error("Incomplete JSON document");
        }
        boolean requiresDocument = options.mode() == JsonReadOptions.Mode.SINGLE_VALUE
                || options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS;
        if (requiresDocument && (roots == 0 || !selected)) {
            throw error("Empty document or selected JSON path missing");
        }
        return Result.end();
    }

    private void validateRoot(Event event, int tokenDepth) {
        if (event.token() == JsonToken.FIELD_NAME) {
            return;
        }
        if (tokenDepth == 0) {
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
            frames.peek().field = event.name();
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
            recording = false;
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
