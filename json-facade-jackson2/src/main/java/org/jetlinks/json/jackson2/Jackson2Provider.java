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
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonProvider;

/** ServiceLoader provider for Jackson 2. All configuration precedes mapper publication.
 * Native adaptation copies host configuration before enforcing facade resource contracts.
 * @since 1.0.0
 */
public final class Jackson2Provider implements JsonProvider {
    /** Snapshot an already configured JSON mapper, including native modules and inclusion policies.
     * The source and its factory are never mutated. Custom serializers/handlers are shared as in
     * ObjectMapper.copy() and must be thread-safe; subsequent source configuration is not followed.
     * @param source fully configured JSON mapper supporting ObjectMapper.copy()
     * @return independent facade with the default materialized-value byte budget
     * @since 1.0.0
     * @see #adapt(ObjectMapper, int)
     */
    public static JsonMapper adapt(ObjectMapper source) {
        return adapt(source, JsonOptions.DEFAULT.maxValueBytes());
    }

    /** Snapshot native binding settings while enforcing facade IO and structural contracts.
     * Native read/write depth limits are aligned to their minimum. Other native read constraints
     * remain intact. options() reports native flags and budgets; omitNulls reports recognized
     * global value-inclusion policies, not content/type/property/custom-filter inclusion details.
     * @param source fully configured JSON mapper; non-JSON factories are unsupported
     * @param maxValueBytes positive byte budget for a materialized incremental record
     * @return thread-safe facade; caller-owned IO stays open and reads require one complete root
     * @throws JsonException with CONFIGURATION kind if native copying or adaptation fails
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
            return new Jackson2Mapper(snapshot, options);
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
        return new Jackson2Mapper(mapper, options);
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
            public JsonObject deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValue.of(context.readValue(input, Object.class)).asObject();
            }
        });
        module.addDeserializer(JsonArray.class, new JsonDeserializer<>() {
            @Override
            public JsonArray deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValue.of(context.readValue(input, Object.class)).asArray();
            }
        });
        module.addSerializer(JsonValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(JsonValue value, JsonGenerator output, SerializerProvider context) throws IOException {
                context.defaultSerializeValue(JsonValues.bindingValue(value), output);
            }
        });
        module.addDeserializer(JsonValue.class, new JsonDeserializer<>() {
            @Override
            public JsonValue deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return JsonValue.of(context.readValue(input, Object.class));
            }

            @Override
            public JsonValue getNullValue(DeserializationContext context) {
                return JsonValue.NULL;
            }
        });
        return module;
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

    /** Exact-class lookup prevents Jackson's inherited serializer matching from widening the SPI. */
    private static final class ExactSerializers extends com.fasterxml.jackson.databind.module.SimpleSerializers {
        private static final long serialVersionUID = 1L;

        @Override
        public JsonSerializer<?> findSerializer(com.fasterxml.jackson.databind.SerializationConfig config,
                                                  com.fasterxml.jackson.databind.JavaType type,
                                                  com.fasterxml.jackson.databind.BeanDescription description) {
            com.fasterxml.jackson.databind.type.ClassKey key = new com.fasterxml.jackson.databind.type.ClassKey(type.getRawClass());
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
            return adapter.read(new Jackson2Reader(input));
        }
    }
}
