package org.jetlinks.json.internal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonToken;

/** Shared framing/selection state machine. Providers supply incremental tokens, never whole documents.
 * Providers buffer and bind the current record's native tokens; no neutral tree round trip is needed.
 */
public abstract class AbstractReadSession<T> implements JsonReadSession<T> {
    /** Borrowed session-local token state, valid until nextEvent; never exposed to callers.
     * A null token indicates an input boundary or genuine EOF, distinguished by exhausted.
     */
    protected static final class Event {
        private JsonToken token;
        private String name;
        private long start;
        private long end;
        private long line;
        private long endLine;
    }

    private final Event currentEvent = new Event();

    /** Reuse token metadata rather than allocating an internal wrapper for every native token. */
    protected final Event event(JsonToken token, String name, long start, long end, long line, long endLine) {
        currentEvent.token = token;
        currentEvent.name = name;
        currentEvent.start = start;
        currentEvent.end = end;
        currentEvent.line = line;
        currentEvent.endLine = endLine;
        return currentEvent;
    }

    private final JsonReadOptions options;
    private final int maxValueBytes;
    private final boolean events;
    private final boolean arrayElements;
    private final List<String> target;
    private final long[] targetIndices;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private boolean recording;
    private int depth;
    private int recordDepth;
    private long recordStart;
    private long recordLine;
    private long lastRecordLine;
    private long previousRootEnd = -1;
    private boolean rootSeen;
    private boolean selectionMatched;
    private boolean closed;
    private boolean inputEnded;
    private boolean pendingCarriageReturn;

    protected AbstractReadSession(JsonReadOptions options, int maxValueBytes) {
        this(options, maxValueBytes, false);
    }

    protected AbstractReadSession(JsonReadOptions options, int maxValueBytes, boolean events) {
        this.options = options;
        this.maxValueBytes = Math.min(options.maxValueBytes(), maxValueBytes);
        this.events = events;
        JsonReadOptions.Selection selection = options.selection();
        arrayElements = selection == null
                ? options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS : selection.arrayElements();
        target = selection == null ? List.of() : selection.pointer().segments();
        targetIndices = new long[target.size()];
        for (int i = 0; i < target.size(); i++) {
            targetIndices[i] = JsonArrayIndex.parse(target.get(i));
        }
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
    /** Emit one immutable public event without buffering its enclosing container. */
    protected abstract T bindEvent(JsonToken token);

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
        return read(Result::new, cancelled);
    }

    @Override
    public final <R> R read(Receiver<? super T, R> receiver, BooleanSupplier cancelled) {
        ensureOpen();
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(cancelled, "cancelled");
        // Dispatch once per read, keeping event-mode work out of the typed record token loop.
        return events ? readEvents(receiver, cancelled) : readValues(receiver, cancelled);
    }

    private <R> R readValues(Receiver<? super T, R> receiver, BooleanSupplier cancelled) {
        while (!cancelled.getAsBoolean()) {
            Event event = nextEvent();
            if (event.token == null) {
                return exhausted() ? finishDocument(receiver) : receiver.receive(State.NEED_INPUT, null);
            }
            int tokenDepth = advanceDepth(event.token);
            // Ordinary root/array reads need only depth; paths would allocate once per input value.
            int matched = tracksPath() ? advancePath(event) : -1;
            validateRoot(event, tokenDepth);
            if (shouldStart(event, matched, tokenDepth)) {
                startRecord();
                recording = true;
                recordDepth = tokenDepth;
                recordStart = event.start;
                recordLine = event.line;
            }
            if (recording) {
                checkSize(event);
                appendToken();
                if (!isStart(event.token) && depth == recordDepth) {
                    return finishRecord(event, receiver);
                }
            }
        }
        return receiver.receive(State.END, null);
    }

    private <R> R readEvents(Receiver<? super T, R> receiver, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return receiver.receive(State.END, null);
        }
        Event event = nextEvent();
        if (event.token == null) {
            return exhausted() ? finishDocument(receiver) : receiver.receive(State.NEED_INPUT, null);
        }
        int tokenDepth = advanceDepth(event.token);
        validateRoot(event, tokenDepth);
        if (event.end - event.start + 1 > maxValueBytes) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON token exceeds byte budget");
        }
        return receiver.receive(State.VALUE, bindEvent(event.token));
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

    private boolean shouldStart(Event event, int matched, int tokenDepth) {
        if (recording || event.token == JsonToken.FIELD_NAME || isEnd(event.token)) {
            return false;
        }
        return tracksPath() ? shouldStartSelected(event.token, matched)
                : shouldStartRoot(event.token, tokenDepth);
    }

    private boolean shouldStartRoot(JsonToken token, int tokenDepth) {
        if (arrayElements && tokenDepth == 0 && token != JsonToken.START_ARRAY) {
            throw error("Selected value must be an array");
        }
        // Root framing already enforces uniqueness; ordinary reads need no selection state.
        return tokenDepth == (arrayElements ? 1 : 0);
    }

    private boolean shouldStartSelected(JsonToken token, int matched) {
        if (matched == target.size()) {
            if (selectionMatched) {
                throw error("Selected JSON path occurs more than once");
            }
            // Even an empty selected array counts as a match and cannot occur again.
            selectionMatched = true;
            if (arrayElements && token != JsonToken.START_ARRAY) {
                throw error("Selected value must be an array");
            }
            return !arrayElements;
        }
        return arrayElements && matched == target.size() + 1;
    }

    private <R> R finishRecord(Event event, Receiver<? super T, R> receiver) {
        if (options.mode() == JsonReadOptions.Mode.NDJSON) {
            if (recordLine != event.endLine || recordLine == lastRecordLine) {
                throw error("NDJSON requires one complete value per line");
            }
            lastRecordLine = recordLine;
        }
        recording = false;
        return receiver.receive(State.VALUE, bindRecord());
    }

    private void checkSize(Event event) {
        long size = event.end - recordStart + 1;
        if (size > maxValueBytes) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON record exceeds byte budget");
        }
    }

    private <R> R finishDocument(Receiver<? super T, R> receiver) {
        if (recording || depth != 0) {
            throw error("Incomplete JSON document");
        }
        if (requiresSingleRoot() && (!rootSeen || tracksPath() && !selectionMatched)) {
            throw error("Empty document or selected JSON path missing");
        }
        return receiver.receive(State.END, null);
    }

    private void validateRoot(Event event, int tokenDepth) {
        if (tokenDepth != 0 || event.token == JsonToken.FIELD_NAME) {
            return;
        }
        if (!isEnd(event.token)) {
            if (rootSeen) {
                if (requiresSingleRoot()) {
                    throw error("Trailing root value");
                }
                if (event.start <= previousRootEnd + 1) {
                    throw error("Root values must be separated by whitespace");
                }
            }
            rootSeen = true;
        }
        if (isEnd(event.token) || !isStart(event.token)) {
            previousRootEnd = event.end;
        }
    }

    private boolean requiresSingleRoot() {
        return options.mode() == JsonReadOptions.Mode.SINGLE_VALUE
                || options.mode() == JsonReadOptions.Mode.ARRAY_ELEMENTS;
    }

    private int advancePath(Event event) {
        JsonToken token = event.token;
        if (token == JsonToken.FIELD_NAME) {
            frames.peek().field = event.name;
            return frames.peek().matched;
        }
        if (isEnd(token)) {
            return frames.pop().matched;
        }
        int matched = 0;
        if (!frames.isEmpty()) {
            Frame parent = frames.peek();
            matched = matchChild(parent);
            if (parent.array) {
                parent.index++;
            }
        }
        if (isStart(token)) {
            frames.push(new Frame(matched, token == JsonToken.START_ARRAY));
        }
        return matched;
    }

    private int matchChild(Frame parent) {
        int matched = parent.matched;
        if (matched == target.size()) {
            return matched + 1;
        }
        if (matched < 0 || matched > target.size()) {
            return -1;
        }
        boolean matches = parent.array ? parent.index == targetIndices[matched]
                : target.get(matched).equals(parent.field);
        return matches ? matched + 1 : -1;
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
            currentEvent.name = null;
            closeParser();
        }
    }

    private static final class Frame {
        private final int matched;
        private final boolean array;
        private String field;
        private long index;

        private Frame(int matched, boolean array) {
            this.matched = matched;
            this.array = array;
        }
    }
}
