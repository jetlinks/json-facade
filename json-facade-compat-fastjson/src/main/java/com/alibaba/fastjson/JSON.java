package com.alibaba.fastjson;

import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;

/**
 * fastjson 1.x 原包名的源码迁移入口，容器共享此基类，绑定和输出委托 JsonMapper。
 * 使用前须移除原 fastjson 依赖；默认入口使用 Json.global，实例使用构造时的映射器。
 * 仅兼容常用源码 API；语法、日期、null 和引用规则由所选 Provider 决定。
 * @deprecated 新代码使用 JsonMapper、JsonObject、JsonArray 和 JsonType。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public abstract class JSON implements JsonValue, JsonValues.DelegatingValue, JSONAware, JSONStreamAware {
    private static final JsonType<Map<String, Object>> OBJECT =
            JsonType.mapOf(JsonType.of(String.class), JsonType.of(Object.class));
    private static final JsonType<List<Object>> ARRAY = JsonType.listOf(JsonType.of(Object.class));
    private final JsonMapper mapper;

    /** @deprecated 供迁移容器固定映射器；新代码使用 JsonMapper.createObject/createArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    protected JSON(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    final JsonMapper mapper() {
        return mapper;
    }

    /** @deprecated 解析对象；null/空白返回 null。使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static JSONObject parseObject(String input) {
        return parseObject(Json.global(), input);
    }

    /** @deprecated 指定映射器解析对象；使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static JSONObject parseObject(JsonMapper mapper, String input) {
        return read(mapper, input, JSONObject.class);
    }

    /** @deprecated 使用 JsonOperationOptions 代替读取 Feature。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static JSONObject parseObject(String input, Feature... features) {
        return read(LegacyOptions.reader(features), input, JSONObject.class);
    }

    /** @deprecated 解析数组；null/空白返回 null。使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static JSONArray parseArray(String input) {
        return parseArray(Json.global(), input);
    }

    /** @deprecated 指定映射器解析数组；使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static JSONArray parseArray(JsonMapper mapper, String input) {
        return read(mapper, input, JSONArray.class);
    }

    /** @deprecated 使用 JsonMapper.read 与 JsonType.listOf 读取普通列表。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> List<T> parseArray(String input, Class<T> type) {
        return read(Json.global(), input, JsonType.listOf(JsonType.of(type)).type());
    }

    /** @deprecated 读取任意根值；使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(String input) {
        return parse(Json.global(), input);
    }

    /** @deprecated 指定映射器读取，容器按需适配；使用 JsonMapper.readTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(JsonMapper mapper, String input) {
        return legacy(mapper, read(mapper, input, Object.class));
    }

    /** @deprecated 使用 JsonOperationOptions 代替 Feature。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(String input, Feature... features) {
        return parse(LegacyOptions.reader(features), input);
    }

    /** @deprecated 使用 JsonOperationOptions 代替位标记；不支持的位明确失败。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(String input, int features) {
        return parse(LegacyOptions.reader(features), input);
    }

    /** @deprecated UTF-8 读取；使用 JsonMapper.read。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(byte[] input, Feature... features) {
        JsonMapper mapper = LegacyOptions.reader(features);
        return legacy(mapper, read(mapper, input, Object.class));
    }

    /** @deprecated 绑定 DTO，规则遵循 Provider；使用 JsonMapper.read。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, Class<T> type) {
        return parseObject(Json.global(), input, type);
    }

    /** @deprecated 仅根兼容容器提供适配；DTO 属性请迁移到中立接口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(JsonMapper mapper, String input, Class<T> type) {
        return read(mapper, input, type);
    }

    /** @deprecated 绑定 DTO 并应用选项；使用 JsonMapper.withOptions。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, Class<T> type, Feature... features) {
        return read(LegacyOptions.reader(features), input, type);
    }

    /** @deprecated 使用 JsonMapper.read；此中立泛型入口无需 TypeReference。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, JsonType<T> type) {
        return read(Json.global(), input, type.type());
    }

    /** @deprecated 使用 JsonMapper.read 和 JsonType.of(Type)。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, Type type, Feature... features) {
        return read(LegacyOptions.reader(features), input, type);
    }

    /** @deprecated 使用 JsonType 匿名子类捕获泛型。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, TypeReference<T> type, Feature... features) {
        Type target = type.getType();
        return read(LegacyOptions.reader(features), input, target);
    }

    /** @deprecated UTF-8 泛型读取；使用 JsonMapper.read(byte[], JsonType)。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(byte[] input, Type type, Feature... features) {
        return read(LegacyOptions.reader(features), input, type);
    }

    /** @deprecated 遵循默认映射器输出；使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value) {
        return write(Json.global(), value);
    }

    /** @deprecated 使用 JsonOperationOptions；只映射 PrettyFormat、WriteMapNullValue。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, SerializerFeature... features) {
        return write(LegacyOptions.writer(features), value);
    }

    /** @deprecated 使用 JsonOperationOptions.pretty；false 显式关闭缩进。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, boolean pretty) {
        return write(LegacyOptions.pretty(pretty), value);
    }

    /** @deprecated 使用 JsonOperationOptions；不支持的位在转换前失败。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, int features, SerializerFeature... extra) {
        return write(LegacyOptions.writer(features, extra), value);
    }

    /** @deprecated 使用 JsonMapper.write(value, Writer)。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static void writeJSONStringTo(Object value, Writer output, SerializerFeature... features) {
        writeJSONString(output, value, features);
    }

    /** @deprecated 已有 JSON 保持原引用，POJO 交给 Provider 转换；使用 JsonMapper.toTree。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object toJSON(Object value) {
        if (value instanceof JSON) {
            return value;
        }
        JsonMapper mapper = Json.global();
        return legacy(mapper, convert(mapper, value, Object.class));
    }

    /** @deprecated 使用 JsonMapper.convert；仅根旧容器提供适配。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T toJavaObject(Object value, Class<T> type) {
        return convert(Json.global(), value, type);
    }

    /** @deprecated 使用构造时的映射器输出；使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public String toJSONString() {
        return write(mapper, this);
    }

    /** @deprecated 本次选项不改变原映射器；使用 JsonMapper.withOptions(...).write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public String toString(SerializerFeature... features) {
        return write(LegacyOptions.writer(mapper, features), this);
    }

    /** @deprecated 读取 UTF-8 流且不关闭输入；使用 JsonMapper.read。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(InputStream input, Type type, Feature... features) throws IOException {
        JsonMapper mapper = LegacyOptions.reader(features);
        if (input == null) {
            return null;
        }
        try {
            return root(mapper, mapper.read(input, bindingType(type)), type);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    /** @deprecated UTF-8 输出；使用 JsonMapper.writeBytes。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static byte[] toJSONBytes(Object value, SerializerFeature... features) {
        try {
            return LegacyOptions.writer(features).writeBytes(value);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    /** @deprecated 输出字符流且不关闭调用方输出；使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static void writeJSONString(Writer output, Object value, SerializerFeature... features) {
        JsonMapper mapper = LegacyOptions.writer(features);
        try {
            mapper.write(value, output);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    /** @deprecated 流式输出 UTF-8 并返回字节数，不关闭输出；使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static int writeJSONString(OutputStream output, Object value, SerializerFeature... features) throws IOException {
        JsonMapper mapper = LegacyOptions.writer(features);
        CountingOutput counter = new CountingOutput(output);
        try {
            mapper.write(value, counter);
            return counter.count;
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    /** @deprecated Writer 直接流式输出，其他 Appendable 输出完整字符串；使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public void writeJSONString(Appendable output) {
        Objects.requireNonNull(output, "output");
        if (output instanceof Writer writer) {
            try {
                mapper.write(this, writer);
            } catch (JsonException error) {
                throw failure(error);
            }
        } else {
            try {
                output.append(toJSONString());
            } catch (IOException error) {
                throw new JSONException("JSON append failed", error);
            }
        }
    }

    /** @deprecated 使用 JsonValue.as 或 JsonMapper.convert；转换规则由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T toJavaObject(Class<T> type) {
        return convert(mapper, this, type);
    }

    /** @deprecated 使用 JsonValue.as 或 JsonMapper.convert；转换规则由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T toJavaObject(Type type) {
        return convert(mapper, this, type);
    }

    /** @deprecated 使用 JsonValue.as 或 JsonMapper.convert；转换规则由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T toJavaObject(TypeReference<T> type) {
        return convert(mapper, this, type.getType());
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static Object parse(String input, ParserConfig config, Feature... features) {
        throw unsupported("parse(ParserConfig)", "JsonMapper.withOptions / Provider configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static <T> T parseObject(String input, Type type, ParserConfig config, Feature... features) {
        throw unsupported("parseObject(ParserConfig)", "JsonMapper.withOptions / Provider configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, SerializeConfig config, SerializerFeature... features) {
        throw unsupported("toJSONString(SerializeConfig)", "Provider adapter / native configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, SerializeFilter filter, SerializerFeature... features) {
        throw unsupported("toJSONString(SerializeFilter)", "Provider adapter / native configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONString(Object value, SerializeFilter[] filters, SerializerFeature... features) {
        throw unsupported("toJSONString(SerializeFilter[])", "Provider adapter / native configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static byte[] toJSONBytes(Object value, SerializeFilter filter, SerializerFeature... features) {
        throw unsupported("toJSONBytes(SerializeFilter)", "Provider adapter / native configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static String toJSONStringWithDateFormat(Object value, String format, SerializerFeature... features) {
        throw unsupported("toJSONStringWithDateFormat", "Provider date/time configuration");
    }

    /** @deprecated 不执行旧引擎配置；立即抛 UnsupportedOperationException，使用提示中的替代入口。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static void setDefaultTypeKey(String key) {
        throw unsupported("setDefaultTypeKey", "explicit Provider polymorphism configuration");
    }

    @Override
    public final boolean isNull() {
        return false;
    }

    @Override
    public final boolean isMissing() {
        return false;
    }

    @Override
    public final int size() {
        return delegateValue().size();
    }

    @Override
    public final JsonValue node(String name) {
        return delegateValue().node(name);
    }

    @Override
    public final JsonValue node(int index) {
        return delegateValue().node(index);
    }

    @Override
    public final Object toJavaValue() {
        return delegateValue().toJavaValue();
    }

    @Override
    public final <T> T as(JsonType<T> type) {
        return delegateValue().as(type);
    }

    @Override
    public final JsonPointer pointer() {
        return delegateValue().pointer();
    }

    @Override
    public final String displayPath() {
        return delegateValue().displayPath();
    }

    @Override
    public final JsonValue at(JsonPointer pointer) {
        return pointer.segments().isEmpty() ? this : delegateValue().at(pointer);
    }

    @Override
    public String toString() {
        return toJSONString();
    }

    static UnsupportedOperationException unsupported(String api, String replacement) {
        return new UnsupportedOperationException(api + " is not supported; use " + replacement);
    }

    static JSONException failure(JsonException error) {
        return new JSONException(error.getMessage(), error);
    }

    static <T> T scalar(JsonValue node, JsonType<T> type) {
        try {
            return node.as(type);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    final <T> T member(JsonValue node, Type type) {
        return node.isNull() || node.isMissing() ? null : convert(mapper, node, type);
    }

    static <T> T convert(JsonMapper mapper, Object value, Type type) {
        try {
            return root(mapper, mapper.convert(value, bindingType(type)), type);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    private static <T> T read(JsonMapper mapper, String input, Type type) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return root(mapper, mapper.read(input, bindingType(type)), type);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    private static <T> T read(JsonMapper mapper, byte[] input, Type type) {
        if (input == null || input.length == 0) {
            return null;
        }
        try {
            return root(mapper, mapper.read(input, bindingType(type)), type);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    private static String write(JsonMapper mapper, Object value) {
        try {
            return mapper.write(value);
        } catch (JsonException error) {
            throw failure(error);
        }
    }

    private static JsonType<?> bindingType(Type type) {
        if (type == JSONObject.class) {
            return OBJECT;
        }
        return type == JSONArray.class ? ARRAY : JsonType.of(type);
    }

    // 目标泛型由公开 Class/Type/TypeReference 入口提供；只在根适配旧容器，不扫描 DTO 图。
    @SuppressWarnings("unchecked")
    private static <T> T root(JsonMapper mapper, Object value, Type type) {
        if (type == JSONObject.class) {
            return (T) object(mapper, value);
        }
        return (T) (type == JSONArray.class ? array(mapper, value) : value);
    }

    static Object legacy(JsonMapper mapper, Object value) {
        if (value instanceof JSON) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return new JSONObject(mapper, stringMap(map));
        }
        if (value instanceof List<?> list) {
            return new JSONArray(mapper, objectList(list));
        }
        return value;
    }

    static JSONObject object(JsonMapper mapper, Object value) {
        Object result = legacy(mapper, value);
        if (result == null || result instanceof JSONObject) {
            return (JSONObject) result;
        }
        throw new JSONException("Expected a JSON object");
    }

    static JSONArray array(JsonMapper mapper, Object value) {
        Object result = legacy(mapper, value);
        if (result == null || result instanceof JSONArray) {
            return (JSONArray) result;
        }
        throw new JSONException("Expected a JSON array");
    }

    // JSON 和中立容器约定 String 键；只恢复泛型，不扫描或复制存储。
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> objectList(List<?> value) {
        return (List<Object>) value;
    }

    private static final class CountingOutput extends FilterOutputStream {
        private int count;

        private CountingOutput(OutputStream target) {
            super(Objects.requireNonNull(target, "output"));
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}

