package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.internal.JsonTreeBinding;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonProvider;

/**
 * Jackson 2 的 ServiceLoader Provider，可跨构建复用；发布前完成全部配置。
 * 原生适配先复制宿主配置，再落实 facade 的资源契约，不修改源映射器。
 * 默认关闭 default typing 并拒绝 JSON 类名类型标识；原生策略显式重开或替换时在准备阶段警告。
 *
 * @since 1.0.0
 * @see JsonProvider
 */
public final class Jackson2Provider implements JsonProvider {
    private static final System.Logger LOG = System.getLogger(Jackson2Provider.class.getName());
    // 在读取类名、加载子类之前拒绝；NAME 映射仍由原生实现处理。
    private static final PolymorphicTypeValidator CLASS_NAMES_DISABLED = new PolymorphicTypeValidator.Base() {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Validity validateBaseType(com.fasterxml.jackson.databind.cfg.MapperConfig<?> config,
                                         com.fasterxml.jackson.databind.JavaType baseType) {
            return Validity.DENIED;
        }
    };

    /**
     * 为已完成配置的 JSON 映射器创建快照，保留原生模块及包含策略。
     * 源映射器和工厂不变；自定义序列化器及处理器按 {@code ObjectMapper.copy()} 语义共享，须线程安全。
     * 后续源配置变化不会同步到快照。
     * 默认原生映射器会关闭类名类型标识；宿主显式配置的多态策略保留并输出 WARNING。
     *
     * @param source 已完成配置且支持 {@code ObjectMapper.copy()} 的 JSON 映射器
     * @return 配置独立的 facade，使用默认增量单值字节预算
     * @since 1.0.0
     * @see #adapt(ObjectMapper, int)
     */
    public static JsonMapper adapt(ObjectMapper source) {
        return adapt(source, JsonOptions.DEFAULT.maxValueBytes());
    }

    /**
     * 保留原生绑定配置的快照，落实 facade 的 I/O 和结构契约。
     * <p>读写深度上限统一为两者较小值，其他原生读取限制保留。
     * {@code options()} 报告原生标志和预算，其中 omitNulls 仅概括已识别的全局值包含策略，
     * 不描述内容、类型、属性或自定义过滤器的全部规则。
     *
     * @param source 已完成配置的 JSON 映射器，不支持非 JSON 工厂
     * @param maxValueBytes 正整数，增量单条物化记录的字节预算
     * @return 可并发共享的 facade；调用方 I/O 保持打开，整值读取须恰好一个完整根值
     * @throws JsonException 原生复制或适配失败时抛出，类别为 CONFIGURATION
     * @since 1.0.0
     */
    public static JsonMapper adapt(ObjectMapper source, int maxValueBytes) {
        Objects.requireNonNull(source, "source");
        if (maxValueBytes < 1) {
            throw new IllegalArgumentException("maxValueBytes must be positive");
        }
        require("JSON".equals(source.getFactory().getFormatName()), "JSON format required for adaptation");
        try {
            ObjectMapper snapshot = source.copy();
            // 默认原生映射器也落实类名禁用策略；宿主显式配置的多态保留并警告，不修改源对象。
            var sourceConfig = snapshot.getDeserializationConfig();
            if (sourceConfig.getDefaultTyper(null) == null
                    && sourceConfig.getPolymorphicTypeValidator() == LaissezFaireSubTypeValidator.instance) {
                snapshot.setPolymorphicTypeValidator(CLASS_NAMES_DISABLED);
            }
            JsonFactory factory = snapshot.getFactory();
            int depth = Math.min(factory.streamReadConstraints().getMaxNestingDepth(),
                    factory.streamWriteConstraints().getMaxNestingDepth());
            factory.setStreamReadConstraints(factory.streamReadConstraints().rebuild()
                    .maxNestingDepth(depth).build());
            factory.setStreamWriteConstraints(factory.streamWriteConstraints().rebuild()
                    .maxNestingDepth(depth).build());
            factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            factory.disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
            factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            factory.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            snapshot.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            snapshot.registerModule(treeModule());
            var limits = factory.streamReadConstraints();
            var inclusion = snapshot.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion();
            boolean omitNulls = switch (inclusion) {
                case NON_NULL, NON_ABSENT, NON_EMPTY, NON_DEFAULT -> true;
                default -> false;
            };
            JsonOptions options = new JsonOptions(
                    snapshot.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                    snapshot.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                    factory.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION), omitNulls,
                    snapshot.isEnabled(SerializationFeature.INDENT_OUTPUT), depth,
                    limits.getMaxStringLength(), limits.getMaxNumberLength(), maxValueBytes);
            warnTypeDiscovery(snapshot.getDeserializationConfig());
            return bindTree(snapshot, options);
        } catch (RuntimeException error) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "Cannot adapt native JSON mapper", error);
        }
    }

    @Override
    public String id() {
        return "jackson2";
    }

    @Override
    public JsonMapper create(JsonConfiguration configuration) {
        JsonOptions options = configuration.options();
        JsonFactory factory = JsonFactory.builder()
                .streamWriteConstraints(com.fasterxml.jackson.core.StreamWriteConstraints.builder()
                        .maxNestingDepth(options.maxDepth()).build())
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(options.maxDepth()).maxStringLength(options.maxStringLength())
                        .maxNumberLength(options.maxNumberLength()).build())
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, options.failDuplicateKeys())
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT).build();
        com.fasterxml.jackson.databind.json.JsonMapper.Builder builder =
                com.fasterxml.jackson.databind.json.JsonMapper.builder(factory)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, options.failUnknownProperties())
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, options.losslessNumbers())
                .configure(SerializationFeature.INDENT_OUTPUT, options.pretty())
                .deactivateDefaultTyping()
                .polymorphicTypeValidator(CLASS_NAMES_DISABLED)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        builder.defaultPropertyInclusion(JsonInclude.Value.construct(
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS,
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS));
        SimpleModule module = new SimpleModule("json-facade");
        module.setSerializers(new ExactSerializers());
        builder.addModule(treeModule());
        JsonModuleContext context = new AdapterContext(module);
        configuration.modules().forEach(extension -> extension.configure(context));
        builder.addModule(module);
        configuration.configureNative(builder);
        var mapper = builder.build();
        validateNativeConfiguration(mapper, options);
        warnTypeDiscovery(mapper.getDeserializationConfig());
        return bindTree(mapper, options);
    }

    static void warnTypeDiscovery(com.fasterxml.jackson.databind.DeserializationConfig config) {
        // 只诊断显式原生配置，不扫描 DTO；原生校验器负责 CLASS/MINIMAL_CLASS 类型标识。
        if (config.getDefaultTyper(null) != null || config.getPolymorphicTypeValidator() != CLASS_NAMES_DISABLED) {
            LOG.log(System.Logger.Level.WARNING,
                    "Jackson 2 已显式配置原生多态或覆盖 JSON 类名禁用策略；请限制可解析的类型，避免处理不可信的类型声明。");
        }
    }

    private static JsonMapper bindTree(ObjectMapper nativeMapper, JsonOptions options) {
        JsonTreeBinding binding = new JsonTreeBinding();
        var config = nativeMapper.getDeserializationConfig();
        nativeMapper.setConfig(config.with(config.getAttributes().withSharedAttribute(JsonTreeBinding.class, binding)));
        return binding.initialize(new Jackson2Mapper(nativeMapper, options));
    }

    private static void validateNativeConfiguration(com.fasterxml.jackson.databind.json.JsonMapper mapper,
                                                    JsonOptions options) {
        // Native modules remain extensible, while shared policies must describe the published mapper.
        require(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS), "single-root reads");
        require(!mapper.isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE), "caller-owned input");
        require(!mapper.isEnabled(StreamWriteFeature.AUTO_CLOSE_TARGET), "caller-owned output");
        require(!mapper.isEnabled(StreamWriteFeature.AUTO_CLOSE_CONTENT), "incomplete-document handling");
        require(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                == options.failUnknownProperties(), "JsonOptions.failUnknownProperties");
        require(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                == options.losslessNumbers(), "JsonOptions.losslessNumbers");
        require(mapper.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                == options.failDuplicateKeys(), "JsonOptions.failDuplicateKeys");
        require(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT) == options.pretty(), "JsonOptions.pretty");
        var inclusion = mapper.getSerializationConfig().getDefaultPropertyInclusion();
        var expected = options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS;
        require(inclusion.getValueInclusion() == expected && inclusion.getContentInclusion() == expected,
                "JsonOptions.omitNulls (global inclusion)");
        var readLimits = mapper.tokenStreamFactory().streamReadConstraints();
        require(readLimits.getMaxNestingDepth() == options.maxDepth(), "JsonOptions.maxDepth (read)");
        require(mapper.tokenStreamFactory().streamWriteConstraints().getMaxNestingDepth() == options.maxDepth(),
                "JsonOptions.maxDepth (write)");
        require(readLimits.getMaxStringLength() == options.maxStringLength(), "JsonOptions.maxStringLength");
        require(readLimits.getMaxNumberLength() == options.maxNumberLength(), "JsonOptions.maxNumberLength");
    }

    private static void require(boolean compatible, String policy) {
        if (!compatible) {
            throw new JsonException(JsonException.Kind.CONFIGURATION,
                    "Native JSON configuration conflicts with " + policy);
        }
    }

    private static SimpleModule treeModule() {
        SimpleModule module = new SimpleModule("json-facade-tree");
        module.addDeserializer(JsonObject.class, new JsonDeserializer<>() {
            @Override
            public JsonObject getNullValue(DeserializationContext context) {
                return null;
            }

            @Override
            public JsonObject deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context)).asObject();
            }
        });
        module.addDeserializer(JsonArray.class, new JsonDeserializer<>() {
            @Override
            public JsonArray getNullValue(DeserializationContext context) {
                return null;
            }

            @Override
            public JsonArray deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context)).asArray();
            }
        });
        module.addSerializer(JsonValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(JsonValue value, JsonGenerator output, SerializerProvider context)
                    throws IOException {
                context.defaultSerializeValue(JsonValues.bindingValue(value), output);
            }
        });
        module.addDeserializer(JsonValue.class, new JsonDeserializer<>() {
            @Override
            public JsonValue deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context));
            }

            @Override
            public JsonValue getNullValue(DeserializationContext context) {
                return JsonValue.NULL;
            }
        });
        return module;
    }

    private static JsonMapper treeMapper(DeserializationContext context) {
        return ((JsonTreeBinding) context.getAttribute(JsonTreeBinding.class)).mapper();
    }

    private static final class AdapterContext implements JsonModuleContext {
        private final SimpleModule module;
        private final Set<Class<?>> registered = new HashSet<>();

        private AdapterContext(SimpleModule module) {
            this.module = module;
        }

        @Override
        public <T> void add(Class<T> type, JsonAdapter<T> adapter) {
            java.util.Objects.requireNonNull(adapter, "adapter");
            if (!registered.add(java.util.Objects.requireNonNull(type, "type"))) {
                throw new JsonException(JsonException.Kind.CONFIGURATION, "Duplicate adapter: " + type.getName());
            }
            module.addSerializer(type, new AdapterSerializer<>(adapter));
            module.addDeserializer(type, new AdapterDeserializer<>(adapter));
        }
    }

    /**
     * 按精确类型查找，避免 Jackson 的继承匹配扩大 SPI 适配范围。
     */
    private static final class ExactSerializers extends com.fasterxml.jackson.databind.module.SimpleSerializers {
        private static final long serialVersionUID = 1L;

        @Override
        public JsonSerializer<?> findSerializer(com.fasterxml.jackson.databind.SerializationConfig config,
                                                com.fasterxml.jackson.databind.JavaType type,
                                                com.fasterxml.jackson.databind.BeanDescription description) {
            com.fasterxml.jackson.databind.type.ClassKey key =
                    new com.fasterxml.jackson.databind.type.ClassKey(type.getRawClass());
            if (_classMappings != null && _classMappings.containsKey(key)) {
                return _classMappings.get(key);
            }
            return _interfaceMappings == null ? null : _interfaceMappings.get(key);
        }
    }

    private static final class AdapterSerializer<T> extends JsonSerializer<T> {
        private final JsonAdapter<T> adapter;

        private AdapterSerializer(JsonAdapter<T> adapter) {
            this.adapter = adapter;
        }

        @Override
        public void serialize(T value, JsonGenerator output, SerializerProvider context) {
            adapter.write(value, new Jackson2Writer(output, context));
        }
    }

    private static final class AdapterDeserializer<T> extends JsonDeserializer<T> {
        private final JsonAdapter<T> adapter;

        private AdapterDeserializer(JsonAdapter<T> adapter) {
            this.adapter = adapter;
        }

        @Override
        public T deserialize(JsonParser input, DeserializationContext context) {
            return adapter.read(new Jackson2Reader(input, context));
        }
    }
}
