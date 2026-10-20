package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonMapperBuilder;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.tck.JsonMapperContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jackson2ContractTest extends JsonMapperContract {
    @Override
    protected JsonMapper mapper(JsonOptions options, JsonModule... modules) {
        JsonMapperBuilder builder = Json.builder().options(options).provider(new Jackson2Provider());
        for (JsonModule module : modules) {
            builder.module(module);
        }
        return builder.build();
    }

    @Test
    void discoversProviderAndAppliesNativeModulesAndMixinsBeforePublication() {
        assertEquals("jackson2", new Jackson2Provider().id());
        SimpleModule module = new SimpleModule("native-fixture");
        module.addSerializer(NativeValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(NativeValue value, JsonGenerator output, SerializerProvider context) throws IOException {
                output.writeString("native:" + value.value());
            }
        });
        JsonMapper json = Json.builder().provider("jackson2")
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, builder -> {
                    builder.addMixIn(Name.class, NameMixin.class);
                    builder.addModule(module);
                }).build();
        assertEquals("{\"native_name\":\"n\"}", json.write(new Name("n")));
        assertEquals(new Name("n"), json.read("{\"native_name\":\"n\"}", Name.class));
        assertEquals("\"native:v\"", json.write(new NativeValue("v")));
        Json.registerGlobal(json);
        Json.registerGlobal("native-fixture", json);
        assertSame(json, Json.global());
        assertSame(json, Json.defaultMapper());
        assertSame(json, Json.global("native-fixture"));
        assertEquals("\"native:v\"", Json.global().write(new NativeValue("v")));
        assertEquals("{\"native_name\":\"n\"}", Json.global("native-fixture").write(new Name("n")));
        assertEquals(3, Json.defaultMapper().read("3", Integer.class));
    }

    @Test
    void callbacksRunInOrderForEachBuildWithoutSharingNativeState() {
        List<Integer> order = new ArrayList<>();
        Jackson2Provider provider = new Jackson2Provider();
        JsonMapperBuilder builder = Json.builder().provider(provider)
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, nativeBuilder -> {
                    order.add(1);
                    if (order.size() == 1) {
                        nativeBuilder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                    }
                })
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        nativeBuilder -> order.add(2));
        JsonMapper first = builder.build();
        JsonMapper second = builder.build();
        assertEquals(List.of(1, 2, 1, 2), order);
        assertEquals("{\"display_name\":\"n\"}", first.write(new Name("n")));
        assertEquals("{\"displayName\":\"n\"}", second.write(new Name("n")));
        assertEquals(second.write(new Name("n")), Json.builder().provider(provider).build().write(new Name("n")));
        assertEquals("{\"display_name\":\"n\"}", first.write(new Name("n")));
    }

    @Test
    void incompatibleNativeTypesFailBeforeAnyCallbackRuns() {
        AtomicInteger invoked = new AtomicInteger();
        JsonException error = assertThrows(JsonException.class, () -> Json.builder().provider("jackson2")
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> invoked.incrementAndGet())
                .configureNative(StringBuilder.class, builder -> invoked.incrementAndGet()).build());
        assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
        assertEquals(0, invoked.get());
    }

    @Test
    void nativeSettingsCannotDisableSharedPoliciesOrOwnershipContracts() {
        List<Consumer<com.fasterxml.jackson.databind.json.JsonMapper.Builder>> conflicts = List.of(
                builder -> builder.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                builder -> builder.enable(StreamReadFeature.AUTO_CLOSE_SOURCE),
                builder -> builder.enable(StreamWriteFeature.AUTO_CLOSE_TARGET),
                builder -> builder.enable(StreamWriteFeature.AUTO_CLOSE_CONTENT),
                builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                builder -> builder.disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION),
                builder -> builder.enable(SerializationFeature.INDENT_OUTPUT),
                builder -> builder.defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)),
                builder -> builder.defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.ALWAYS, JsonInclude.Include.NON_NULL)),
                builder -> ((JsonFactory) builder.streamFactory()).setStreamReadConstraints(
                        ((JsonFactory) builder.streamFactory()).streamReadConstraints().rebuild()
                                .maxNestingDepth(4).build()),
                builder -> ((JsonFactory) builder.streamFactory()).setStreamWriteConstraints(
                        ((JsonFactory) builder.streamFactory()).streamWriteConstraints().rebuild()
                                .maxNestingDepth(4).build()),
                builder -> ((JsonFactory) builder.streamFactory()).setStreamReadConstraints(
                        ((JsonFactory) builder.streamFactory()).streamReadConstraints().rebuild()
                                .maxStringLength(4).build()),
                builder -> ((JsonFactory) builder.streamFactory()).setStreamReadConstraints(
                        ((JsonFactory) builder.streamFactory()).streamReadConstraints().rebuild()
                                .maxNumberLength(4).build()));
        for (var conflict : conflicts) {
            JsonException error = assertThrows(JsonException.class, () -> Json.builder().provider("jackson2")
                    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, conflict).build());
            assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
            assertTrue(error.getMessage().contains("conflicts with"));
        }
        JsonOptions defaults = JsonOptions.DEFAULT;
        JsonOptions pretty = new JsonOptions(true, false, false, false, true, defaults.maxDepth(),
                defaults.maxStringLength(), defaults.maxNumberLength(), defaults.maxValueBytes());
        JsonMapper mapper = Json.builder().provider("jackson2").options(pretty)
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> builder.enable(SerializationFeature.INDENT_OUTPUT)).build();
        assertSame(pretty, mapper.options());
        assertTrue(mapper.write(new Name("n")).contains("\n"));
    }

    public record Name(String displayName) {
    }

    public record NativeValue(String value) {
    }

    abstract static class NameMixin {
        @JsonProperty("native_name")
        abstract String displayName();
    }
}
