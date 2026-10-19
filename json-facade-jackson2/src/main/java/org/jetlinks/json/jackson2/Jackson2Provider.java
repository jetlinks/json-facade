package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.util.HashSet;
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
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonNativeExtension;
import org.jetlinks.json.spi.JsonProvider;

/** ServiceLoader provider for Jackson 2. All configuration precedes mapper publication.
 * No Spring integration, global mapper or late mutation.
 * @since 1.0.0
 */
public final class Jackson2Provider implements JsonProvider {
    @Override
    public String id() {
        return "jackson2";
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
        installTree(module);
        JsonModuleContext context = new AdapterContext(module);
        configuration.modules().forEach(extension -> extension.configure(context));
        builder.addModule(module);
        for (JsonNativeExtension extension : configuration.extensions()) {
            if (!(extension instanceof Jackson2Extension nativeExtension)) {
                throw new JsonException(JsonException.Kind.CONFIGURATION, "Unsupported Jackson 2 extension");
            }
            nativeExtension.configure().accept(builder);
        }
        return new Jackson2Mapper(builder.build(), options);
    }

    private static void installTree(SimpleModule module) {
        module.addSerializer(JsonValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(JsonValue value, JsonGenerator output, SerializerProvider context) throws IOException {
                output.writeObject(value.toJavaValue());
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
            adapter.write(value, new Jackson2Writer(output));
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
