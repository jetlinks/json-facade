package org.jetlinks.json;

import java.util.Objects;

/** Immutable structural streaming event. Token text retains the tokenizer's scalar representation.
 * Value events bind a bounded Java value using the configured mapper; callers keep it stable until used.
 * @since 1.0.0
 */
public sealed interface JsonEvent permits JsonEvent.Token, JsonEvent.Value {
    Token START_OBJECT = new Token(JsonToken.START_OBJECT, null);
    Token END_OBJECT = new Token(JsonToken.END_OBJECT, null);
    Token START_ARRAY = new Token(JsonToken.START_ARRAY, null);
    Token END_ARRAY = new Token(JsonToken.END_ARRAY, null);
    Token NULL = new Token(JsonToken.NULL, null);
    Token TRUE = new Token(JsonToken.BOOLEAN, "true");
    Token FALSE = new Token(JsonToken.BOOLEAN, "false");

    static Token name(String name) {
        return new Token(JsonToken.FIELD_NAME, name);
    }

    static Token string(String value) {
        return new Token(JsonToken.STRING, value);
    }

    static Token number(String text) {
        return new Token(JsonToken.NUMBER, text);
    }

    static Value value(Object value) {
        return new Value(value);
    }

    /** Structural tokens are shared constants; text-bearing events own immutable Strings. */
    static Token token(JsonToken token, String text) {
        if (text != null && token != JsonToken.FIELD_NAME && token != JsonToken.STRING
                && token != JsonToken.NUMBER && token != JsonToken.BOOLEAN) {
            throw new IllegalArgumentException("Structural tokens have no text");
        }
        return switch (token) {
            case START_OBJECT -> START_OBJECT;
            case END_OBJECT -> END_OBJECT;
            case START_ARRAY -> START_ARRAY;
            case END_ARRAY -> END_ARRAY;
            case NULL -> NULL;
            case BOOLEAN -> "true".equals(text) ? TRUE : "false".equals(text) ? FALSE : new Token(token, text);
            default -> new Token(token, text);
        };
    }

    /** Exactly one neutral JSON token, excluding input-boundary and EOF markers. */
    record Token(JsonToken token, String text) implements JsonEvent {
        public Token {
            Objects.requireNonNull(token, "token");
            switch (token) {
                case FIELD_NAME, STRING, NUMBER -> Objects.requireNonNull(text, "text");
                case BOOLEAN -> {
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw new IllegalArgumentException("Invalid boolean token");
                    }
                }
                default -> {
                    if (text != null) {
                        throw new IllegalArgumentException("Structural tokens have no text");
                    }
                }
            }
        }
    }

    /** One complete value, including Java null; nested structures may instead use separate events. */
    record Value(Object value) implements JsonEvent {
    }
}
