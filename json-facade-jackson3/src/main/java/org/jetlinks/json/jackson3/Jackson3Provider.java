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
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonNativeExtension;
import org.jetlinks.json.spi.JsonProvider;

/** ServiceLoader provider for Jackson 3. All configuration precedes mapper publication.
 * No Spring integration, global mapper or late mutation.
 * @since 1.0.0
 */
public final class Jackson3Provider implements JsonProvider {
    @Override
    public String id() {
        return "jackson3";
    }

    @Override
    public JsonMapper create(JsonConfiguration configuration) {
        JsonOptions options = configuration.options();
        JsonFactory factory = JsonFactory.builder()
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
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        builder.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS,
                options.omitNulls() ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS));
        SimpleModule module = new SimpleModule("json-facade");
        module.setSerializers(new ExactSerializers());
        installTree(module);
        JsonModuleContext context = new AdapterContext(module);
        configuration.modules().forEach(extension -> extension.configure(context));
        builder.addModule(module);
        for (JsonNativeExtension extension : configuration.extensions()) {
            if (!(extension instanceof Jackson3Extension nativeExtension)) {
                throw new JsonException(JsonException.Kind.CONFIGURATION, "Unsupported Jackson 3 extension");
            }
            nativeExtension.configure().accept(builder);
        }
        return new Jackson3Mapper(builder.build(), options);
    }

    private static void installTree(SimpleModule module) {
        module.addSerializer(JsonValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(JsonValue value, JsonGenerator output, SerializationContext context) throws tools.jackson.core.JacksonException {
                output.writePOJO(value.toJavaValue());
            }
        });
        module.addDeserializer(JsonValue.class, new ValueDeserializer<>() {
            @Override
            public JsonValue deserialize(JsonParser input, DeserializationContext context) throws tools.jackson.core.JacksonException {
                return JsonValue.of(context.readValue(input, Object.class));
            }

            @Override
            public JsonValue getNullValue(DeserializationContext context) {
                return JsonValue.NULL;
            }
        });
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
            adapter.write(value, new Jackson3Writer(output));
        }
    }

    private static final class AdapterDeserializer<T> extends ValueDeserializer<T> {
        private final JsonAdapter<T> adapter;

        private AdapterDeserializer(JsonAdapter<T> adapter) {
            this.adapter = adapter;
        }

        @Override
        public T deserialize(JsonParser input, DeserializationContext context) {
            return adapter.read(new Jackson3Reader(input));
        }
    }
}
