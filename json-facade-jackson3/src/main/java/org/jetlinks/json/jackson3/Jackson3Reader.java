package org.jetlinks.json.jackson3;

import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;

/** Borrowable cursor used both for standalone reads and nested custom adapter calls. */
final class Jackson3Reader implements JsonReader {
    private JsonParser parser;

    Jackson3Reader(JsonParser parser) {
        this.parser = parser;
    }

    @Override
    public JsonToken next() {
        return Jackson3Support.call(() -> token(parser().nextToken()));
    }

    @Override
    public JsonToken current() {
        return token(parser().currentToken());
    }

    @Override
    public String text() {
        return Jackson3Support.call(parser()::getString);
    }

    @Override
    public <T> T readValue(JsonType<T> type) {
        // Keep the hot binding call free of captured actions, even without JIT scalar replacement.
        try {
            JsonParser input = parser();
            return input.objectReadContext().readValue(input, new NativeType<T>(type.type()));
        } catch (Exception failure) {
            throw Jackson3Support.failure(failure);
        }
    }

    @Override
    public void skipValue() {
        Jackson3Support.call(parser()::skipChildren);
    }

    @Override
    public void close() {
        // A retained closed cursor must not retain its caller's source or native buffers.
        JsonParser owned = parser;
        parser = null;
        if (owned != null) {
            Jackson3Support.call(() -> {
                owned.close();
                return null;
            });
        }
    }

    private JsonParser parser() {
        if (parser == null) {
            throw new IllegalStateException("JSON reader is closed");
        }
        return parser;
    }

    static JsonToken token(tools.jackson.core.JsonToken token) {
        if (token == null || token == tools.jackson.core.JsonToken.NOT_AVAILABLE) {
            return null;
        }
        return switch (token) {
            case START_OBJECT -> JsonToken.START_OBJECT;
            case END_OBJECT -> JsonToken.END_OBJECT;
            case START_ARRAY -> JsonToken.START_ARRAY;
            case END_ARRAY -> JsonToken.END_ARRAY;
            case PROPERTY_NAME -> JsonToken.FIELD_NAME;
            case VALUE_STRING -> JsonToken.STRING;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> JsonToken.NUMBER;
            case VALUE_TRUE, VALUE_FALSE -> JsonToken.BOOLEAN;
            case VALUE_NULL -> JsonToken.NULL;
            default -> throw new IllegalStateException("Not a JSON token: " + token);
        };
    }

    /** Resolves the neutral Type within the current configured binding context. */
    private static final class NativeType<T> extends TypeReference<T> {
        private final java.lang.reflect.Type type;

        private NativeType(java.lang.reflect.Type type) {
            this.type = type;
        }

        @Override
        public java.lang.reflect.Type getType() {
            return type;
        }
    }
}
