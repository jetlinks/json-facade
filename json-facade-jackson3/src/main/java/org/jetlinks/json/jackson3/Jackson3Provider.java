package org.jetlinks.json.jackson3;

import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
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
 * Jackson 3 的 ServiceLoader Provider，可跨构建复用；发布前完成全部配置。
 * 不注册全局映射器，不进行发布后配置变更；Spring 桥接由对应模块提供。
 * 默认关闭 default typing 并拒绝 JSON 类名类型标识；原生策略显式重开或替换时在准备阶段警告。
 *
 * @since 1.0.0
 * @see JsonProvider
 */
public final class Jackson3Provider implements JsonProvider {
    private static final System.Logger LOG = System.getLogger(Jackson3Provider.class.getName());
    // 在读取类名、加载子类之前拒绝；NAME 映射仍由原生实现处理。
    private static final PolymorphicTypeValidator CLASS_NAMES_DISABLED = new PolymorphicTypeValidator.Base() {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Validity validateBaseType(tools.jackson.databind.DatabindContext context,
                                         tools.jackson.databind.JavaType baseType) {
            return Validity.DENIED;
        }
    };
    @Override
    public String id() {
        return "jackson3";
    }

    @Override
    public JsonMapper create(JsonConfiguration configuration) {
        JsonOptions options = configuration.options();
        JsonFactory factory = JsonFactory.builder()
                .streamWriteConstraints(tools.jackson.core.StreamWriteConstraints.builder()
                        .maxNestingDepth(options.maxDepth()).build())
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(options.maxDepth()).maxStringLength(options.maxStringLength())
                        .maxNumberLength(options.maxNumberLength()).build())
                .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION, options.failDuplicateKeys())
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                .disable(StreamWriteFeature.AUTO_CLOSE_CONTENT).build();
        tools.jackson.databind.json.JsonMapper.Builder builder =
                tools.jackson.databind.json.JsonMapper.builder(factory)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, options.failUnknownProperties())
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, options.losslessNumbers())
                .configure(SerializationFeature.INDENT_OUTPUT, options.pretty())
                .deactivateDefaultTyping()
                .polymorphicTypeValidator(CLASS_NAMES_DISABLED)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        builder.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS,
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS));
        SimpleModule module = new SimpleModule("json-facade");
        module.setSerializers(new ExactSerializers());
        SimpleModule treeModule = new SimpleModule("json-facade-tree");
        installTree(treeModule);
        builder.addModule(treeModule);
        JsonModuleContext context = new AdapterContext(module);
        configuration.modules().forEach(extension -> extension.configure(context));
        builder.addModule(module);
        configuration.configureNative(builder);
        JsonTreeBinding binding = new JsonTreeBinding();
        var attributes = builder.defaultAttributes();
        if (attributes == null) {
            attributes = tools.jackson.databind.cfg.ContextAttributes.getEmpty();
        }
        builder.defaultAttributes(attributes.withSharedAttribute(JsonTreeBinding.class, binding));
        var mapper = builder.build();
        validateNativeConfiguration(mapper, options);
        warnTypeDiscovery(mapper.deserializationConfig());
        return binding.initialize(new Jackson3Mapper(mapper, options));
    }

    static void warnTypeDiscovery(tools.jackson.databind.DeserializationConfig config) {
        // 只诊断显式原生配置，不扫描 DTO；原生校验器负责 CLASS/MINIMAL_CLASS 类型标识。
        if (config.getDefaultTyper(null) != null || config.getPolymorphicTypeValidator() != CLASS_NAMES_DISABLED) {
            LOG.log(System.Logger.Level.WARNING,
                    "Jackson 3 已显式配置原生多态或覆盖 JSON 类名禁用策略；请限制可解析的类型，避免处理不可信的类型声明。");
        }
    }

    private static void validateNativeConfiguration(tools.jackson.databind.json.JsonMapper mapper,
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
        var inclusion = mapper.serializationConfig().getDefaultPropertyInclusion();
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

    private static void installTree(SimpleModule module) {
        module.addDeserializer(JsonObject.class, new ValueDeserializer<>() {
            @Override
            public JsonObject getNullValue(DeserializationContext context) {
                return JsonValues.objectView(JsonValues.of(null, treeMapper(context)));
            }

            @Override
            public JsonObject deserialize(JsonParser input, DeserializationContext context)
                    throws tools.jackson.core.JacksonException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context)).asObject();
            }
        });
        module.addDeserializer(JsonArray.class, new ValueDeserializer<>() {
            @Override
            public JsonArray getNullValue(DeserializationContext context) {
                return JsonValues.arrayView(JsonValues.of(null, treeMapper(context)));
            }

            @Override
            public JsonArray deserialize(JsonParser input, DeserializationContext context)
                    throws tools.jackson.core.JacksonException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context)).asArray();
            }
        });
        module.addSerializer(JsonValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(JsonValue value, JsonGenerator output, SerializationContext context)
                    throws tools.jackson.core.JacksonException {
                context.writeValue(output, JsonValues.bindingValue(value));
            }
        });
        module.addDeserializer(JsonValue.class, new ValueDeserializer<>() {
            @Override
            public JsonValue deserialize(JsonParser input, DeserializationContext context)
                    throws tools.jackson.core.JacksonException {
                return JsonValues.of(context.readValue(input, Object.class), treeMapper(context));
            }

            @Override
            public JsonValue getNullValue(DeserializationContext context) {
                return JsonValue.NULL;
            }
        });
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
    private static final class ExactSerializers extends tools.jackson.databind.module.SimpleSerializers {
        private static final long serialVersionUID = 1L;

        @Override
        protected ValueSerializer<?> _findSerializer(tools.jackson.databind.SerializationConfig config,
                                                    tools.jackson.databind.JavaType type) {
            tools.jackson.databind.type.ClassKey key = new tools.jackson.databind.type.ClassKey(type.getRawClass());
            if (_classMappings != null && _classMappings.containsKey(key)) {
                return _classMappings.get(key);
            }
            return _interfaceMappings == null ? null : _interfaceMappings.get(key);
        }
    }

    private static final class AdapterSerializer<T> extends ValueSerializer<T> {
        private final JsonAdapter<T> adapter;

        private AdapterSerializer(JsonAdapter<T> adapter) {
            this.adapter = adapter;
        }

        @Override
        public void serialize(T value, JsonGenerator output, SerializationContext context) {
            adapter.write(value, new Jackson3Writer(output, context));
        }
    }

    private static final class AdapterDeserializer<T> extends ValueDeserializer<T> {
        private final JsonAdapter<T> adapter;

        private AdapterDeserializer(JsonAdapter<T> adapter) {
            this.adapter = adapter;
        }

        @Override
        public T deserialize(JsonParser input, DeserializationContext context) {
            return adapter.read(new Jackson3Reader(input, context));
        }
    }
}
