package org.jetlinks.json.jackson2;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriter;

/** Immutable facade over a fully configured native mapper; the native instance is never exposed. */
final class Jackson2Mapper implements JsonMapper {
    private final ObjectMapper mapper;
    private final JsonOptions options;

    Jackson2Mapper(ObjectMapper mapper, JsonOptions options) {
        this.mapper = mapper;
        this.options = options;
    }

    @Override
    public JsonOptions options() {
        return options;
    }

    @Override
    public void write(Object value, OutputStream output) {
        Jackson2Support.call(() -> {
            mapper.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void write(Object value, Writer output) {
        Jackson2Support.call(() -> {
            mapper.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public <T> T read(InputStream input, JsonType<T> type) {
        return Jackson2Support.call(() -> mapper.readValue(input, mapper.constructType(type.type())));
    }

    @Override
    public <T> T read(Reader input, JsonType<T> type) {
        return Jackson2Support.call(() -> mapper.readValue(input, mapper.constructType(type.type())));
    }

    @Override
    public <T> T convert(Object value, JsonType<T> type) {
        return Jackson2Support.call(() -> mapper.convertValue(unwrap(value), mapper.constructType(type.type())));
    }

    @Override
    public JsonReader reader(InputStream input) {
        return Jackson2Support.call(() -> new Jackson2Reader(mapper.getFactory().createParser(input)));
    }

    @Override
    public JsonWriter writer(OutputStream output) {
        return Jackson2Support.call(() -> new Jackson2Writer(mapper.getFactory().createGenerator(output)));
    }

    @Override
    public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions readOptions) {
        return Jackson2Support.call(() -> new Jackson2Session<>(this, mapper, type, readOptions));
    }

    private static Object unwrap(Object value) {
        return value instanceof JsonValue node ? node.toJavaValue() : value;
    }
}
