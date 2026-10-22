package org.jetlinks.json.jackson2;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectReader;
import org.jetlinks.json.JsonAttribute;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;

/** Borrowable cursor used both for standalone reads and nested custom adapter calls. */
final class Jackson2Reader implements JsonReader {
    private JsonParser parser;
    private ObjectReader valueReader;
    private DeserializationContext context;

    Jackson2Reader(JsonParser parser, ObjectReader reader) {
        this.parser = parser;
        valueReader = reader.without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Jackson2Reader(JsonParser parser, DeserializationContext context) {
        this.parser = parser;
        this.context = context;
    }

    @Override
    public <T> T attribute(JsonAttribute<T> key) {
        parser();
        return key.cast(context == null ? valueReader.getAttributes().getAttribute(key) : context.getAttribute(key));
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
        // A captured action can allocate per value when an iterator adds another call boundary.
        try {
            JsonParser input = parser();
            if (context != null) {
                T value = context.readValue(input, context.constructType(type.type()));
                // 上下文绑定保留末令牌；中立游标统一清除，next() 再推进后继。
                input.clearCurrentToken();
                return value;
            }
            return valueReader.forType(type.type()).readValue(input);
        } catch (Exception failure) {
            throw Jackson2Support.failure(failure);
        }
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
        valueReader = null;
        context = null;
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
