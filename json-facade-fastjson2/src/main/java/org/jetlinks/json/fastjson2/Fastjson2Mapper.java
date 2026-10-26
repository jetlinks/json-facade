package org.jetlinks.json.fastjson2;

import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Callable;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.jetlinks.json.CloseableIterator;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.spi.JsonConfiguration;

/** 原生上下文在发布前配置完成；操作仅创建原生 reader/writer，不共享输入、输出或引用解析任务。 */
final class Fastjson2Mapper implements JsonMapper {
    private final JsonConfiguration configuration;
    private final JsonOperationOptions operation;
    private final JsonOptions options;
    private final JSONReader.Context reader;
    private final JSONWriter.Context writer;
    private final Fastjson2Mapper compactMapper;

    Fastjson2Mapper(JsonConfiguration configuration, JsonOperationOptions operation, JsonOptions options,
                    JSONReader.Context reader, JSONWriter.Context writer, Fastjson2Mapper compactMapper) {
        this.configuration = configuration;
        this.operation = operation;
        this.options = options;
        this.reader = reader;
        this.writer = writer;
        this.compactMapper = compactMapper;
    }

    @Override
    public JsonOptions options() {
        return options;
    }

    @Override
    public JsonMapper withOptions(JsonOperationOptions overrides) {
        return Objects.requireNonNull(overrides, "overrides").isEmpty() ? this
                : Fastjson2Provider.prepare(configuration, operation.overlay(overrides));
    }

    @Override
    public <T> T read(String input, JsonType<T> type) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(type, "type");
        return call(() -> bind(JSONReader.of(input, reader), type));
    }

    @Override
    public <T> T read(byte[] input, JsonType<T> type) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(type, "type");
        return call(() -> bind(JSONReader.of(input, reader), type));
    }

    @Override
    public <T> T read(InputStream input, JsonType<T> type) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(type, "type");
        return call(() -> bind(JSONReader.of(new BorrowedInput(input), StandardCharsets.UTF_8, reader), type));
    }

    @Override
    public <T> T read(Reader input, JsonType<T> type) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(type, "type");
        return call(() -> bind(JSONReader.of(new BorrowedReader(input), reader), type));
    }

    private static <T> T bind(JSONReader input, JsonType<T> type) {
        try (input) {
            if (input.isEnd()) {
                throw new JsonException(JsonException.Kind.SYNTAX, "Expected one JSON value");
            }
            T value = input.read(type.type());
            input.handleResolveTasks(value);
            // hasComma 保留嵌套字段的消费状态，完整根值检查沿用原生公开解析入口的 EOF 语义。
            if (!input.isEnd()) {
                throw new JsonException(JsonException.Kind.SYNTAX, "Expected end of JSON input");
            }
            return value;
        }
    }

    private JSONWriter encode(Object value, boolean compact) {
        if (compact && compactMapper != null) {
            return compactMapper.encode(value, false);
        }
        JSONWriter output = JSONWriter.ofUTF8(writer);
        boolean complete = false;
        try {
            Object unwrapped = JsonValues.bindingValue(value);
            output.setRootObject(unwrapped);
            output.writeAny(unwrapped);
            complete = true;
            return output;
        } finally {
            // 编码失败时仍归还原生缓冲，成功后由接收返回值的 try-with-resources 释放。
            if (!complete) {
                output.close();
            }
        }
    }

    @Override
    public byte[] writeBytes(Object value) {
        return call(() -> {
            try (JSONWriter output = encode(value, false)) {
                return output.getBytes();
            }
        });
    }

    @Override
    public String write(Object value) {
        return call(() -> {
            try (JSONWriter output = encode(value, false)) {
                return output.toString();
            }
        });
    }

    @Override
    public void write(Object value, OutputStream target) {
        writeTo(value, Objects.requireNonNull(target, "output"), false);
    }

    @Override
    public void writeCompact(Object value, OutputStream target) {
        writeTo(value, Objects.requireNonNull(target, "output"), true);
    }

    private void writeTo(Object value, OutputStream target, boolean compact) {
        call(() -> {
            try (JSONWriter output = encode(value, compact)) {
                output.flushTo(target);
            }
            return null;
        });
    }

    @Override
    public void write(Object value, Writer target) {
        Objects.requireNonNull(target, "output");
        call(() -> {
            try (JSONWriter output = encode(value, false)) {
                output.flushTo(target);
            }
            return null;
        });
    }

    @Override
    public <T> T convert(Object value, JsonType<T> type) {
        Objects.requireNonNull(type, "type");
        // 复用原生 JSON 编解码，保留日期、二进制及自定义处理器的 JSON 表示，不另选二进制协议。
        return read(writeBytes(value), type);
    }

    @Override
    public JsonReader reader(InputStream input) {
        Objects.requireNonNull(input, "input");
        throw unsupported("streaming token reader");
    }

    @Override
    public <T> CloseableIterator<T> iterateArray(InputStream input, JsonType<T> type) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(type, "type");
        throw unsupported("bounded array iterator");
    }

    @Override
    public JsonWriter writer(OutputStream output) {
        Objects.requireNonNull(output, "output");
        throw unsupported("structural writer");
    }

    @Override
    public JsonWriter compactWriter(OutputStream output) {
        Objects.requireNonNull(output, "output");
        throw unsupported("structural writer");
    }

    @Override
    public JsonReadSession<JsonEvent> eventSession() {
        throw unsupported("nonblocking event session");
    }

    @Override
    public <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions readOptions) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(readOptions, "readOptions");
        throw unsupported("nonblocking read session");
    }

    private static JsonException unsupported(String capability) {
        return new JsonException(JsonException.Kind.CONFIGURATION, "fastjson2 does not support facade " + capability);
    }

    private static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (JsonException error) {
            throw error;
        } catch (Exception error) {
            // fastjson2 没有结构化解析/绑定异常分类或路径；保留原因，不解析消息或完整源数据。
            Throwable cause = error;
            while (cause instanceof JSONException && cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            throw new JsonException(cause instanceof IOException ? JsonException.Kind.IO : JsonException.Kind.BINDING,
                    "fastjson2 operation failed", error);
        }
    }

    /** 原生 reader 释放自身资源时，不接管调用方输入的关闭权。 */
    private static final class BorrowedInput extends FilterInputStream {
        private BorrowedInput(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
        }
    }

    /** 字符输入遵循相同的借用契约，不捕获外层映射器。 */
    private static final class BorrowedReader extends FilterReader {
        private BorrowedReader(Reader input) {
            super(input);
        }

        @Override
        public void close() {
        }
    }
}
