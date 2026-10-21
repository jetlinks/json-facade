package org.jetlinks.json.jackson3;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.JsonWriter;

/** Immutable facade over a fully configured native mapper; the native instance is never exposed. */
final class Jackson3Mapper implements JsonMapper {
    private final ObjectMapper mapper;
    private final JsonOptions options;
    private final ObjectWriter compactWriter;

    Jackson3Mapper(ObjectMapper mapper, JsonOptions options) {
        this.mapper = mapper;
        this.options = options;
        compactWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT).withRootValueSeparator("");
    }

    @Override
    public JsonOptions options() {
        return options;
    }

    @Override
    public byte[] writeBytes(Object value) {
        // String output inherits this byte path, preserving its UTF-8 escaping.
        return Jackson3Support.call(() -> mapper.writeValueAsBytes(unwrap(value)));
    }

    @Override
    public void write(Object value, OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson3Support.call(() -> {
            mapper.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void write(Object value, Writer output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson3Support.call(() -> {
            mapper.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void writeCompact(Object value, OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson3Support.call(() -> {
            compactWriter.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public <T> T read(byte[] input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> mapper.readValue(input, mapper.constructType(type.type())));
    }

    @Override
    public <T> T read(InputStream input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> mapper.readValue(input, mapper.constructType(type.type())));
    }

    @Override
    public <T> T read(Reader input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> mapper.readValue(input, mapper.constructType(type.type())));
    }

    @Override
    public <T> T convert(Object value, JsonType<T> type) {
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> mapper.convertValue(unwrap(value), mapper.constructType(type.type())));
    }

    @Override
    public JsonReader reader(InputStream input) {
        java.util.Objects.requireNonNull(input, "input");
        return Jackson3Support.call(() -> new Jackson3Reader(mapper.createParser(input)));
    }

    @Override
    public JsonWriter writer(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson3Support.call(() -> new Jackson3Writer(mapper.createGenerator(output), mapper.writer()));
    }

    @Override
    public JsonWriter compactWriter(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson3Support.call(() -> new Jackson3Writer(compactWriter.createGenerator(output), compactWriter));
    }

    @Override
    public JsonReadSession<JsonEvent> eventSession() {
        return Jackson3Support.call(() -> new Jackson3Session<>(this, mapper, JsonType.of(JsonEvent.class),
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE), true));
    }

    @Override
    public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions readOptions) {
        java.util.Objects.requireNonNull(type, "type").type();
        java.util.Objects.requireNonNull(readOptions, "readOptions");
        return Jackson3Support.call(() -> new Jackson3Session<>(this, mapper, type, readOptions));
    }

    private static Object unwrap(Object value) {
        return JsonValues.bindingValue(value);
    }
}
