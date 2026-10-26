package org.jetlinks.json.jackson3;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.PrettyPrinter;
import tools.jackson.databind.util.TokenBuffer;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
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
final class Jackson3Mapper implements JsonMapper {
    private final ObjectMapper mapper;
    private final JsonOptions options;
    private final ObjectWriter compactWriter;
    private final ObjectReader valueReader;
    private final ObjectWriter valueWriter;
    private final Jackson3Mapper base;
    private final JsonOperationOptions operationOptions;

    Jackson3Mapper(ObjectMapper mapper, JsonOptions options) {
        this(mapper, options, null, JsonOperationOptions.EMPTY, mapper.reader(), mapper.writer());
    }

    Jackson3Mapper(ObjectMapper mapper, JsonOptions options, Jackson3Mapper base,
                   JsonOperationOptions operationOptions, ObjectReader valueReader, ObjectWriter valueWriter) {
        this.mapper = mapper;
        this.options = options;
        this.base = base == null ? this : base;
        this.operationOptions = operationOptions;
        this.valueReader = valueReader;
        this.valueWriter = valueWriter;
        // 流式层在记录/事件边界显式刷新，避免原生 writeValue 与外层重复 flush。
        compactWriter = valueWriter.without(SerializationFeature.INDENT_OUTPUT, SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
                .with((PrettyPrinter) null).withRootValueSeparator("");
    }

    @Override
    public JsonMapper withOptions(JsonOperationOptions overrides) {
        if (java.util.Objects.requireNonNull(overrides, "overrides").isEmpty()) {
            return this;
        }
        return Jackson3Operations.prepare(base.mapper, base.options, base, operationOptions.overlay(overrides));
    }

    @Override
    public JsonOptions options() {
        return options;
    }

    @Override
    public byte[] writeBytes(Object value) {
        // String output inherits this byte path, preserving its UTF-8 escaping.
        return Jackson3Support.call(() -> valueWriter.writeValueAsBytes(unwrap(value)));
    }

    @Override
    public void write(Object value, OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson3Support.call(() -> {
            valueWriter.writeValue(output, unwrap(value));
            return null;
        });
    }

    @Override
    public void write(Object value, Writer output) {
        java.util.Objects.requireNonNull(output, "output");
        Jackson3Support.call(() -> {
            valueWriter.writeValue(output, unwrap(value));
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
        return Jackson3Support.call(() -> base == this
                ? mapper.readValue(input, mapper.constructType(type.type()))
                : valueReader.forType(mapper.constructType(type.type())).readValue(input));
    }

    @Override
    public <T> T read(InputStream input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> base == this
                ? mapper.readValue(input, mapper.constructType(type.type()))
                : valueReader.forType(mapper.constructType(type.type())).readValue(input));
    }

    @Override
    public <T> T read(Reader input, JsonType<T> type) {
        java.util.Objects.requireNonNull(input, "input");
        java.util.Objects.requireNonNull(type, "type").type();
        return Jackson3Support.call(() -> base == this
                ? mapper.readValue(input, mapper.constructType(type.type()))
                : valueReader.forType(mapper.constructType(type.type())).readValue(input));
    }

    @Override
    public <T> T convert(Object value, JsonType<T> type) {
        java.util.Objects.requireNonNull(type, "type").type();
        if (base == this) {
            return Jackson3Support.call(() -> mapper.convertValue(unwrap(value), mapper.constructType(type.type())));
        }
        // 直接使用原生 writer 的转换缓冲能力，保留 generator 的当前绑定上下文。
        return Jackson3Support.call(() -> {
            try (TokenBuffer buffer = valueWriter.without(SerializationFeature.WRAP_ROOT_VALUE)
                    .writeValueIntoBuffer(unwrap(value))) {
                return valueReader.forType(mapper.constructType(type.type()))
                        .without(DeserializationFeature.UNWRAP_ROOT_VALUE).readValue(buffer);
            }
        });
    }

    @Override
    public JsonReader reader(InputStream input) {
        java.util.Objects.requireNonNull(input, "input");
        return Jackson3Support.call(() -> new Jackson3Reader(valueReader.createParser(input), valueReader));
    }

    @Override
    public JsonWriter writer(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson3Support.call(() -> new Jackson3Writer(valueWriter.createGenerator(output), valueWriter));
    }

    @Override
    public JsonWriter compactWriter(OutputStream output) {
        java.util.Objects.requireNonNull(output, "output");
        return Jackson3Support.call(() -> new Jackson3Writer(compactWriter.createGenerator(output), compactWriter));
    }

    @Override
    public JsonReadSession<JsonEvent> eventSession() {
        return Jackson3Support.call(() -> new Jackson3Session<>(this, mapper, valueReader, JsonType.of(JsonEvent.class),
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE), true));
    }

    @Override
    public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions readOptions) {
        java.util.Objects.requireNonNull(type, "type").type();
        java.util.Objects.requireNonNull(readOptions, "readOptions");
        return Jackson3Support.call(() -> new Jackson3Session<>(this, mapper, valueReader, type, readOptions));
    }

    private static Object unwrap(Object value) {
        return JsonValues.bindingValue(value);
    }
}
