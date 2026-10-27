package org.jetlinks.json.fastjson2;

import java.lang.reflect.Type;
import java.util.function.Function;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.modules.ObjectWriterModule;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.writer.ObjectWriter;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonTreeBinding;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonProvider;

/**
 * fastjson2 同步绑定 Provider，可通过 ServiceLoader 或显式实例选择。
 * 使用 {@link #nativeOptions()} 明确采用原生令牌限制；保留深度预算、完整根值和调用方 I/O 所有权。
 * 不支持中立 JsonModule/业务属性、手动游标及非阻塞会话；原生扩展通过 {@link Fastjson2Builder} 配置。
 * 直接绑定中立树（含 DTO 属性）须启用 DisableReferenceDetect，保留 $ref 字段；解析引用后建树用 readTree。
 * 默认禁用 JSON 类名解析；原生配置显式重开时在构建/准备阶段输出 WARNING，不记录输入内容。
 * 每次构建及准备操作变体创建独立原生 Provider，发布后的映射器可并发复用。
 *
 * @since 1.0.0
 * @see JsonProvider
 */
public final class Fastjson2Provider implements JsonProvider {
    private static final System.Logger LOG = System.getLogger(Fastjson2Provider.class.getName());
    /**
     * 返回显式采用 fastjson2 原生字符串/数字限制的配置，其他项沿用平台默认值。
     * <pre>{@code
     * JsonMapper mapper = Json.builder().provider("fastjson2")
     *     .options(Fastjson2Provider.nativeOptions()).build();
     * }</pre>
     * 原生没有对应的有限令牌长度配置；请求有限上限、严格未知字段或重复键检测会在构建时失败。
     * 默认 JsonOptions 不能直接用于此 Provider，避免把其有限上限悄悄忽略。
     *
     * @since 1.0.0
     */
    public static JsonOptions nativeOptions() {
        return JsonOptions.DEFAULT.withMaxStringLength(Integer.MAX_VALUE).withMaxNumberLength(Integer.MAX_VALUE);
    }

    @Override
    public String id() {
        return "fastjson2";
    }

    @Override
    public JsonMapper create(JsonConfiguration configuration) {
        return prepare(configuration, JsonOperationOptions.EMPTY);
    }

    static JsonMapper prepare(JsonConfiguration configuration, JsonOperationOptions operation) {
        return prepare(configuration, operation, true);
    }

    private static JsonMapper prepare(JsonConfiguration configuration, JsonOperationOptions operation, boolean diagnose) {
        JsonOptions options = overlay(configuration.options(), operation);
        require(options.maxStringLength() == Integer.MAX_VALUE
                && options.maxNumberLength() == Integer.MAX_VALUE, "use Fastjson2Provider.nativeOptions()");
        // 原生重复键开关只覆盖部分容器读取，不能把它当作所有 DTO 的统一严格策略。
        require(!options.failDuplicateKeys(), "strict duplicate-key detection is not supported for all target types");
        require(!options.failUnknownProperties(), "strict unknown-property detection is not supported for all target types");
        require(configuration.modules().isEmpty(), "use native ObjectReader/ObjectWriter extensions instead of JsonModule");
        require(operation.attributes().isEmpty(), "operation attributes require a native read-context attachment API");
        try {
            Fastjson2Builder builder = new Fastjson2Builder(options);
            JsonTreeBinding binding = new JsonTreeBinding();
            registerTrees(builder, binding);
            configuration.configureNative(builder);
            operation.validateNativeTypes(builder.reader(), builder.writer());
            JSONReader.Context reader = operation.configureReader(JSONReader.Context.class, builder.reader());
            JSONWriter.Context writer = operation.configureWriter(JSONWriter.Context.class, builder.writer());
            require(reader.getProvider() == builder.reader().getProvider()
                    && writer.getProvider() == builder.writer().getProvider(), "native Provider replacement");
            validate(reader, writer, options);
            // compact 也在准备阶段完成，避免每次 writeCompact 重建配置或修改共享 Context。
            Fastjson2Mapper compact = options.pretty() ? (Fastjson2Mapper) prepare(configuration,
                    operation.overlay(JsonOperationOptions.builder().pretty(false).build()), false) : null;
            // compact 为内部派生，不重复输出相同配置的警告。
            if (diagnose) {
                warnTypeDiscovery(reader);
            }
            return binding.initialize(new Fastjson2Mapper(configuration, operation, options, reader, writer, compact));
        } catch (JsonException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "Cannot configure fastjson2", error);
        }
    }

    @SuppressWarnings("deprecation")
    private static void warnTypeDiscovery(JSONReader.Context reader) {
        if (!reader.getProvider().isDisableAutoType() || reader.isEnabled(JSONReader.Feature.SupportAutoType)
                || reader.isEnabled(JSONReader.Feature.SupportClassForName)
                || reader.getContextAutoTypeBeforeHandler() != null
                || reader.getProvider().getAutoTypeBeforeHandler() != null) {
            LOG.log(System.Logger.Level.WARNING,
                    "fastjson2 已显式开启 JSON 类名解析能力；请限制可解析的类型，避免处理不可信的类型声明。");
        }
    }

    private static JsonOptions overlay(JsonOptions base, JsonOperationOptions operation) {
        return base.withLosslessNumbers(operation.losslessNumbers() == null
                        ? base.losslessNumbers() : operation.losslessNumbers())
                .withFailUnknownProperties(operation.failUnknownProperties() == null
                        ? base.failUnknownProperties() : operation.failUnknownProperties())
                .withFailDuplicateKeys(operation.failDuplicateKeys() == null
                        ? base.failDuplicateKeys() : operation.failDuplicateKeys())
                .withOmitNulls(operation.omitNulls() == null ? base.omitNulls() : operation.omitNulls())
                .withPretty(operation.pretty() == null ? base.pretty() : operation.pretty());
    }

    private static void validate(JSONReader.Context reader, JSONWriter.Context writer, JsonOptions options) {
        require(reader.getMaxLevel() == options.maxDepth() && writer.getMaxLevel() == options.maxDepth(), "maxDepth");
        require(reader.isEnabled(JSONReader.Feature.UseDoubleForDecimals) == !options.losslessNumbers(), "losslessNumbers");
        require(reader.isEnabled(JSONReader.Feature.ErrorOnUnknownProperties) == options.failUnknownProperties(),
                "failUnknownProperties");
        require(!reader.isEnabled(JSONReader.Feature.ErrorOnDuplicateKeys), "strict duplicate-key detection");
        require(!reader.isEnabled(JSONReader.Feature.IgnoreCheckClose), "single-root reads");
        require(writer.isEnabled(JSONWriter.Feature.WriteNulls) == !options.omitNulls(), "omitNulls");
        require(writer.isEnabled(JSONWriter.Feature.PrettyFormat) == options.pretty(), "pretty");
    }

    private static void require(boolean compatible, String policy) {
        if (!compatible) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "fastjson2 configuration: " + policy);
        }
    }

    private static void registerTrees(Fastjson2Builder builder, JsonTreeBinding binding) {
        builder.reader().getProvider().register(JsonValue.class, treeReader(binding, Function.identity(), true));
        builder.reader().getProvider().register(JsonObject.class, treeReader(binding, JsonValues::objectView, false));
        builder.reader().getProvider().register(JsonArray.class, treeReader(binding, JsonValues::arrayView, false));
        ObjectWriter<Object> treeWriter = (output, value, name, type, features) ->
                output.writeAny(JsonValues.bindingValue(value));
        builder.writer().getProvider().register(new ObjectWriterModule() {
            // fastjson2 的公开 SPI 使用原始 Class 参数；这里只适配签名，不做未检查转换。
            @Override
            @SuppressWarnings("rawtypes")
            public ObjectWriter<?> getObjectWriter(Type type, Class objectClass) {
                return JsonValue.class.isAssignableFrom(objectClass) ? treeWriter : null;
            }
        });
    }

    private static <T> ObjectReader<T> treeReader(JsonTreeBinding binding, Function<JsonValue, T> view, boolean neutral) {
        return new ObjectReader<>() {
            @Override
            public T readObject(JSONReader input, Type type, Object name, long features) {
                // 原生引用任务在整根绑定后才执行，不能先复制子树；保留 $ref 字段需显式关闭引用解析。
                require(input.isEnabled(JSONReader.Feature.DisableReferenceDetect),
                        "direct neutral-tree binding requires DisableReferenceDetect; use readTree for resolved references");
                Object value = input.readAny();
                JsonValue tree = neutral && value == null ? JsonValue.NULL : JsonValues.of(value, binding.mapper());
                return view.apply(tree);
            }

        };
    }
}
