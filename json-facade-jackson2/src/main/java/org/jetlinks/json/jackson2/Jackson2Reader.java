package org.jetlinks.json.jackson2;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;

/** Borrowable cursor used both for standalone reads and nested custom adapter calls. */
final class Jackson2Reader implements JsonReader {
    private JsonParser parser;

    Jackson2Reader(JsonParser parser) {
        this.parser = parser;
    }

    @Override
    public JsonToken next() {
        return Jackson2Support.call(() -> token(parser().nextToken()));
    }

    @Override
    public JsonToken current() {
        return token(parser().currentToken());
    }

    @Override
    public String text() {
        return Jackson2Support.call(parser()::getText);
    }

    @Override
    public <T> T readValue(JsonType<T> type) {
        return Jackson2Support.call(() -> {
            JsonParser input = parser();
            ObjectMapper mapper = (ObjectMapper) input.getCodec();
            return mapper.readerFor(mapper.constructType(type.type()))
                    .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(input);
        });
    }

    @Override
    public void skipValue() {
        Jackson2Support.call(parser()::skipChildren);
    }

    @Override
    public void close() {
        // A retained closed cursor must not retain its caller's source or native buffers.
        JsonParser owned = parser;
        parser = null;
        if (owned != null) {
            Jackson2Support.call(() -> {
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

    static JsonToken token(com.fasterxml.jackson.core.JsonToken token) {
        if (token == null || token == com.fasterxml.jackson.core.JsonToken.NOT_AVAILABLE) {
            return null;
        }
        return switch (token) {
            case START_OBJECT -> JsonToken.START_OBJECT;
            case END_OBJECT -> JsonToken.END_OBJECT;
            case START_ARRAY -> JsonToken.START_ARRAY;
            case END_ARRAY -> JsonToken.END_ARRAY;
            case FIELD_NAME -> JsonToken.FIELD_NAME;
            case VALUE_STRING -> JsonToken.STRING;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> JsonToken.NUMBER;
            case VALUE_TRUE, VALUE_FALSE -> JsonToken.BOOLEAN;
            case VALUE_NULL -> JsonToken.NULL;
            default -> throw new IllegalStateException("Not a JSON token: " + token);
        };
    }
}
