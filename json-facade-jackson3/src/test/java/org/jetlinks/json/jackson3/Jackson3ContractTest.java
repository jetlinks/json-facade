package org.jetlinks.json.jackson3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
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

class Jackson3ContractTest extends JsonMapperContract {
    @Override
    protected JsonMapper mapper(JsonOptions options, JsonModule... modules) {
        JsonMapperBuilder builder = Json.builder().options(options).provider(new Jackson3Provider());
        for (JsonModule module : modules) {
            builder.module(module);
        }
        return builder.build();
    }

    @Test
    void discoversProviderAndAppliesNativeModulesAndMixinsBeforePublication() {
        assertEquals("jackson3", new Jackson3Provider().id());
        SimpleModule module = new SimpleModule("native-fixture");
        module.addSerializer(NativeValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(NativeValue value, JsonGenerator output, SerializationContext context) {
                output.writeString("native:" + value.value());
            }
        });
        JsonMapper json = Json.builder().provider("jackson3")
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, builder -> {
                    builder.addMixIn(Name.class, NameMixin.class);
                    builder.addModule(module);
                }).build();
        assertEquals("{\"native_name\":\"n\"}", json.write(new Name("n")));
        assertEquals(new Name("n"), json.read("{\"native_name\":\"n\"}", Name.class));
        assertEquals("\"native:v\"", json.write(new NativeValue("v")));
        assertEquals(3, Json.defaultMapper().read("3", Integer.class));
    }

    @Test
    void callbacksRunInOrderForEachBuildWithoutSharingNativeState() {
        List<Integer> order = new ArrayList<>();
        Jackson3Provider provider = new Jackson3Provider();
        JsonMapperBuilder builder = Json.builder().provider(provider)
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, nativeBuilder -> {
                    order.add(1);
                    if (order.size() == 1) {
                        nativeBuilder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                    }
                })
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class,
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
        JsonException error = assertThrows(JsonException.class, () -> Json.builder().provider("jackson3")
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> invoked.incrementAndGet())
                .configureNative(StringBuilder.class, builder -> invoked.incrementAndGet()).build());
        assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
        assertEquals(0, invoked.get());
    }

    @Test
    void nativeSettingsCannotDisableSharedPoliciesOrOwnershipContracts() {
        List<Consumer<tools.jackson.databind.json.JsonMapper.Builder>> conflicts = List.of(
                builder -> builder.disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS),
                builder -> builder.enable(StreamReadFeature.AUTO_CLOSE_SOURCE),
                builder -> builder.enable(StreamWriteFeature.AUTO_CLOSE_TARGET),
                builder -> builder.enable(StreamWriteFeature.AUTO_CLOSE_CONTENT),
                builder -> builder.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                builder -> builder.disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION),
                builder -> builder.enable(SerializationFeature.INDENT_OUTPUT),
                builder -> builder.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)),
                builder -> builder.changeDefaultPropertyInclusion(inclusion -> JsonInclude.Value.construct(
                        JsonInclude.Include.ALWAYS, JsonInclude.Include.NON_NULL)));
        for (var conflict : conflicts) {
            JsonException error = assertThrows(JsonException.class, () -> Json.builder().provider("jackson3")
                    .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, conflict).build());
            assertEquals(JsonException.Kind.CONFIGURATION, error.kind());
            assertTrue(error.getMessage().contains("conflicts with"));
        }
        JsonOptions defaults = JsonOptions.DEFAULT;
        JsonOptions pretty = new JsonOptions(true, false, false, false, true, defaults.maxDepth(),
                defaults.maxStringLength(), defaults.maxNumberLength(), defaults.maxValueBytes());
        JsonMapper mapper = Json.builder().provider("jackson3").options(pretty)
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class,
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
