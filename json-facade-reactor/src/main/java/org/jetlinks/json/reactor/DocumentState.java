package org.jetlinks.json.reactor;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonWriter;

/** Validates one structural document before invoking the native writer. Memory is bounded by depth. */
final class DocumentState {
    private final JsonOptions options;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private boolean rootWritten;

    DocumentState(JsonOptions options) {
        this.options = options;
    }

    int prefixBytes(JsonEvent event) {
        Frame parent = stack.peek();
        if (parent == null) {
            return 0;
        }
        if (event instanceof JsonEvent.Token token) {
            if (token.token() == JsonToken.END_ARRAY || token.token() == JsonToken.END_OBJECT) {
                return 0;
            }
            if (token.token() == JsonToken.FIELD_NAME) {
                return parent.hasValues ? 1 : 0;
            }
        }
        return parent.array ? (parent.hasValues ? 1 : 0) : 1;
    }

    void write(JsonEvent event, JsonWriter writer) {
        if (event instanceof JsonEvent.Value value) {
            valueStarted();
            writer.value(value.value());
            return;
        }
        JsonEvent.Token token = (JsonEvent.Token) event;
        validateText(token);
        switch (token.token()) {
            case FIELD_NAME -> {
                Frame parent = stack.peek();
                if (parent == null || parent.array || !parent.expectingName) {
                    throw error("Unexpected JSON field name");
                }
                parent.expectingName = false;
                writer.name(token.text());
            }
            case START_OBJECT, START_ARRAY -> start(token.token(), writer);
            case END_OBJECT, END_ARRAY -> end(token.token(), writer);
            default -> scalar(token, writer);
        }
    }

    private void validateText(JsonEvent.Token token) {
        if (token.text() != null) {
            int limit = token.token() == JsonToken.NUMBER
                    ? options.maxNumberLength() : options.maxStringLength();
            if (token.text().length() > limit) {
                throw new JsonException(JsonException.Kind.LIMIT, "JSON token exceeds text budget");
            }
        }
    }

    private void start(JsonToken token, JsonWriter writer) {
        valueStarted();
        if (stack.size() == options.maxDepth()) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON nesting exceeds depth budget");
        }
        boolean array = token == JsonToken.START_ARRAY;
        stack.push(new Frame(array));
        if (array) {
            writer.beginArray();
        } else {
            writer.beginObject();
        }
    }

    private void end(JsonToken token, JsonWriter writer) {
        Frame parent = stack.peek();
        boolean array = token == JsonToken.END_ARRAY;
        if (parent == null || parent.array != array || !parent.expectingName) {
            throw error("Mismatched or incomplete JSON container");
        }
        stack.pop();
        if (array) {
            writer.endArray();
        } else {
            writer.endObject();
        }
    }

    private void scalar(JsonEvent.Token token, JsonWriter writer) {
        valueStarted();
        switch (token.token()) {
            case NUMBER -> writer.number(token.text());
            case STRING -> writer.string(token.text());
            case BOOLEAN -> writer.bool(Boolean.parseBoolean(token.text()));
            case NULL -> writer.nullValue();
            default -> throw error("Expected a JSON scalar");
        }
    }

    private void valueStarted() {
        Frame parent = stack.peek();
        if (parent == null) {
            if (rootWritten) {
                throw error("Trailing root value");
            }
            rootWritten = true;
        } else if (!parent.array) {
            if (parent.expectingName) {
                throw error("Expected a JSON field name");
            }
            parent.expectingName = true;
        }
        if (parent != null) {
            parent.hasValues = true;
        }
    }

    void finish() {
        if (!rootWritten || !stack.isEmpty()) {
            throw error("Incomplete JSON document");
        }
    }

    private static JsonException error(String message) {
        return new JsonException(JsonException.Kind.SYNTAX, message);
    }

    private static final class Frame {
        private final boolean array;
        private boolean expectingName = true;
        private boolean hasValues;

        Frame(boolean array) {
            this.array = array;
        }
    }
}
