package org.jetlinks.json.jackson2;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.PrettyPrinter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.JsonWriter;

/** Immutable facade over a fully configured native mapper; the native instance is never exposed. */
final class Jackson2Mapper implements JsonMapper {
    private final ObjectMapper mapper;
    private final JsonOptions options;
    private final ObjectWriter compactWriter;
    private final ObjectReader valueReader;
    private final ObjectWriter valueWriter;
    private final Jackson2Mapper base;
    private final JsonOperationOptions operationOptions;

    Jackson2Mapper(ObjectMapper mapper, JsonOptions options) {
        this(mapper, options, null, JsonOperationOptions.EMPTY, mapper.reader(), mapper.writer());
    }

    Jackson2Mapper(ObjectMapper mapper, JsonOptions options, Jackson2Mapper base,
                   JsonOperationOptions operationOptions, ObjectReader valueReader, ObjectWriter valueWriter) {
        this.mapper = mapper;
        this.options = options;
        this.base = base == null ? this : base;
        this.operationOptions = operationOptions;
        this.valueReader = valueReader;
        this.valueWriter = valueWriter;
        compactWriter = valueWriter.without(SerializationFeature.INDENT_OUTPUT)
                .with((PrettyPrinter) null).withRootValueSeparator("");
    }

    @Override
    public JsonMapper withOptions(JsonOperationOptions overrides) {
        if (java.util.Objects.requireNonNull(overrides, "overrides").isEmpty()) {
            return this;
        }
        return Jackson2Operations.prepare(base.mapper, base.options, base, operationOptions.overlay(overrides));
    }

    @Override
    public JsonOptions options() {
        return options;
    }

    @Override
    public void write(Object value, OutputStream output) {
        // Default byte output uses this path: native writeValueAsBytes drops raw serializer IO causes.
        java.util.Objects.requireNonNull(output, "output");
        Jackson2Support.call(() -> {
            valueWriter.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void write(Object value, Writer output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson2Support.call(() -> {
            valueWriter.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void writeCompact(Object value, OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson2Support.call(() -> {
            compactWriter.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public <T> T read(InputStream input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson2Support.call(() -> base == this
                ? mapper.readValue(input, mapper.constructType(type.type()))
                : valueReader.forType(mapper.constructType(type.type())).readValue(input));
    }

    @Override
    public <T> T read(Reader input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson2Support.call(() -> base == this
                ? mapper.readValue(input, mapper.constructType(type.type()))
                : valueReader.forType(mapper.constructType(type.type())).readValue(input));
    }

    @Override
    public <T> T convert(Object value, JsonType<T> type) {
        java.util.Objects.requireNonNull(type, "type").type();
        if (base == this) {
            return Jackson2Support.call(() -> mapper.convertValue(unwrap(value), mapper.constructType(type.type())));
        }
        // 使用原生令牌缓冲衔接已配置的读写器，避免字符串或中立树中转。
        return Jackson2Support.call(() -> {
            try (TokenBuffer buffer = new TokenBuffer(mapper, false)) {
                buffer.forceUseOfBigDecimal(options.losslessNumbers());
                valueWriter.without(SerializationFeature.WRAP_ROOT_VALUE).writeValue(buffer, unwrap(value));
                try (var input = buffer.asParser(mapper)) {
                    return valueReader.forType(mapper.constructType(type.type()))
                            .without(DeserializationFeature.UNWRAP_ROOT_VALUE).readValue(input);
                }
            }
        });
    }

    @Override
    public JsonReader reader(InputStream input) {
        java.util.Objects.requireNonNull(input, "input");
        return Jackson2Support.call(() -> new Jackson2Reader(valueReader.createParser(input), valueReader));
    }

    @Override
    public JsonWriter writer(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson2Support.call(() -> new Jackson2Writer(valueWriter.createGenerator(output), valueWriter));
    }

    @Override
    public JsonWriter compactWriter(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson2Support.call(() -> new Jackson2Writer(compactWriter.createGenerator(output),
                compactWriter));
    }

    @Override
    public JsonReadSession<JsonEvent> eventSession() {
        return Jackson2Support.call(() -> new Jackson2Session<>(this, mapper, valueReader, JsonType.of(JsonEvent.class),
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE), true));
    }

    @Override
    public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions readOptions) {
        java.util.Objects.requireNonNull(type, "type").type();
        java.util.Objects.requireNonNull(readOptions, "readOptions");
        return Jackson2Support.call(() -> new Jackson2Session<>(this, mapper, valueReader, type, readOptions));
    }

    private static Object unwrap(Object value) {
        return JsonValues.bindingValue(value);
    }
}
