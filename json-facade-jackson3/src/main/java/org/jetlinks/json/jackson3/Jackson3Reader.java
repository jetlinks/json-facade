package org.jetlinks.json.jackson3;

import java.lang.reflect.Type;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationContext;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonAttribute;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;

/** Borrowable cursor used both for standalone reads and nested custom adapter calls. */
final class Jackson3Reader implements JsonReader {
    private JsonParser parser;
    private ObjectReader valueReader;
    private Type valueType;
    private DeserializationContext context;

    Jackson3Reader(JsonParser parser, ObjectReader reader) {
        this.parser = parser;
        valueReader = reader.without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    Jackson3Reader(JsonParser parser, DeserializationContext context) {
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
            if (valueReader != null) {
                // 只保留当前类型，跳过重复的泛型解析；每值仍有新上下文，避免跨记录保留对象 ID。
                Type target = type.type();
                if (target != valueType) {
                    valueReader = valueReader.forType(valueReader.typeFactory().constructType(target));
                    valueType = target;
                }
                return valueReader.readValue(input);
            }
            T value = context.readValue(input, context.constructType(type.type()));
            // 原生上下文不保证清除末令牌，统一履行中立游标的消费契约。
            input.clearCurrentToken();
            return value;
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
        valueReader = null;
        valueType = null;
        context = null;
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

}
