package org.jetlinks.json.jackson2;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
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
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonMapperBuilder;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.tck.JsonMapperContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void byteAndStringConvenienceOutputsRetainSerializerIoFailures() {
        IOException failure = new IOException("serializer IO");
        SimpleModule module = new SimpleModule("serializer-io");
        module.addSerializer(NativeValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(NativeValue value, JsonGenerator output, SerializerProvider context) throws IOException {
                throw failure;
            }
        });
        JsonMapper json = Json.builder().provider(new Jackson2Provider())
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> builder.addModule(module)).build();
        NativeValue value = new NativeValue("x");
        List<JsonException> errors = List.of(
                assertThrows(JsonException.class, () -> json.write(value, new ByteArrayOutputStream())),
                assertThrows(JsonException.class, () -> json.writeBytes(value)),
                assertThrows(JsonException.class, () -> json.write(value)));
        for (JsonException error : errors) {
            assertEquals(JsonException.Kind.IO, error.kind());
            assertSame(failure, error.getCause());
        }
    }

    @Test
    void convenienceInputsRetainDeserializerIoFailuresAndCloseParsers() {
        IOException failure = new IOException("deserializer IO");
        List<com.fasterxml.jackson.core.JsonParser> parsers = new ArrayList<>();
        SimpleModule module = new SimpleModule("deserializer-io");
        module.addDeserializer(NativeValue.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public NativeValue deserialize(com.fasterxml.jackson.core.JsonParser input,
                    com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                parsers.add(input);
                throw failure;
            }
        });
        JsonMapper json = Json.builder().provider(new Jackson2Provider())
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> builder.addModule(module)).build();
        byte[] bytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<JsonException> errors = List.of(
                assertThrows(JsonException.class, () -> json.read(new java.io.ByteArrayInputStream(bytes),
                        org.jetlinks.json.JsonType.of(NativeValue.class))),
                assertThrows(JsonException.class, () -> json.read(new java.io.StringReader("{}"),
                        org.jetlinks.json.JsonType.of(NativeValue.class))),
                assertThrows(JsonException.class, () -> json.read(bytes, NativeValue.class)),
                assertThrows(JsonException.class, () -> json.read("{}", NativeValue.class)));
        for (JsonException error : errors) {
            assertEquals(JsonException.Kind.IO, error.kind());
            assertSame(failure, error.getCause());
        }
        assertEquals(4, parsers.size());
        parsers.forEach(parser -> assertTrue(parser.isClosed()));
    }

    @Test
    void convenienceInputsKeepConfiguredStreamAndReaderDecoration() {
        var decorator = new com.fasterxml.jackson.core.io.InputDecorator() {
            @Override
            public java.io.InputStream decorate(com.fasterxml.jackson.core.io.IOContext context,
                                                java.io.InputStream input) {
                return new java.io.ByteArrayInputStream("\"stream\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public java.io.InputStream decorate(com.fasterxml.jackson.core.io.IOContext context,
                                                byte[] input, int offset, int length) {
                return new java.io.ByteArrayInputStream("\"bytes\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            @Override
            public java.io.Reader decorate(com.fasterxml.jackson.core.io.IOContext context, java.io.Reader input) {
                return new java.io.StringReader("\"reader\"");
            }
        };
        JsonFactory factory = JsonFactory.builder().inputDecorator(decorator).build();
        JsonMapper json = Jackson2Provider.adapt(com.fasterxml.jackson.databind.json.JsonMapper.builder(factory).build());
        assertEquals("stream", json.read("\"original\"".getBytes(java.nio.charset.StandardCharsets.UTF_8), String.class));
        assertEquals("reader", json.read("\"original\"", String.class));
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
    void treeConversionsHonorNativeCoercionAndRemainIsolated() {
        JsonMapper ordinary = Json.builder().provider(new Jackson2Provider()).build();
        JsonMapper strict = Json.builder().provider(new Jackson2Provider())
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, builder ->
                        builder.withCoercionConfig(com.fasterxml.jackson.databind.type.LogicalType.Integer,
                                coercion -> coercion.setCoercion(
                                        com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
                                        com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)))
                .build();
        assertEquals(12, ordinary.createObject(java.util.Map.of("n", "12")).node("n").asInteger());
        JsonException error = assertThrows(JsonException.class,
                () -> strict.readTree("{\"n\":\"12\"}").node("n").asInteger(99));
        assertEquals(JsonException.Kind.BINDING, error.kind());
        assertEquals("/n", error.pointer().toString());
        assertEquals(99, strict.createObject().node("missing").asInteger(99));
        assertEquals(12, strict.createObject(java.util.Map.of("n", 12)).node("n").asInteger());
    }

    @Test
    void treeBindingCopiesContainersSharedByNativeDeserializers() {
        List<Object> items = new ArrayList<>(List.of(1));
        Map<String, Object> shared = new LinkedHashMap<>(Map.of("items", items));
        SimpleModule module = new SimpleModule("shared-untyped-values");
        module.addDeserializer(Object.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public Object deserialize(com.fasterxml.jackson.core.JsonParser input,
                                      com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                input.skipChildren();
                return shared;
            }
        });
        JsonMapper json = Json.builder().provider(new Jackson2Provider())
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> builder.addModule(module)).build();
        List<JsonValue> trees = List.of(json.readTree("{}"), json.read("{}", JsonValue.class),
                json.read("{}", JsonObject.class), json.toTree(Map.of("ignored", 1)));
        items.add(2);
        shared.put("later", 3);
        for (JsonValue tree : trees) {
            assertEquals(List.of(1), tree.node("items").toJavaValue());
            assertTrue(tree.node("later").isMissing());
            JsonArray nested = tree.getArray("items");
            nested.add(4);
        }
        assertEquals(List.of(1, 2), items);
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

    @Test
    void nativeObjectIdentityIsConfiguredWithoutAFacadeReferenceProtocol() {
        JsonMapper json = referenceMapper();
        ReferenceNode a = new ReferenceNode();
        ReferenceNode b = new ReferenceNode();
        a.id = "a";
        b.id = "b";
        a.next = b;
        b.next = a;
        ReferenceNode copy = json.read(json.write(a), ReferenceNode.class);
        assertSame(copy, copy.next.next);
        var type = org.jetlinks.json.JsonType.listOf(org.jetlinks.json.JsonType.of(ReferenceNode.class));
        var values = json.read(json.write(List.of(a, a)), type);
        assertSame(values.get(0), values.get(1));
    }

    @Test
    void standaloneCursorAndIteratorUseIndependentNativeIdentityContexts() {
        JsonMapper json = referenceMapper();
        String record = "{\"id\":\"a\",\"next\":\"a\"}";
        for (JsonMapper variant : List.of(json,
                json.withOptions(JsonOperationOptions.builder().pretty(true).build()))) {
            byte[] input = ("[" + record + "," + record + "]").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var items = variant.iterateArray(new ByteArrayInputStream(input), ReferenceNode.class)) {
                ReferenceNode first = items.next();
                ReferenceNode second = items.next();
                assertNotSame(first, second);
                assertSame(first, first.next);
                assertSame(second, second.next);
                assertFalse(items.hasNext());
            }
            byte[] crossing = ("[" + record + ",\"a\"]").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (JsonReader cursor = variant.reader(new ByteArrayInputStream(crossing))) {
                assertEquals(JsonToken.START_ARRAY, cursor.next());
                assertEquals(JsonToken.START_OBJECT, cursor.next());
                ReferenceNode first = cursor.readValue(ReferenceNode.class);
                assertSame(first, first.next);
                assertEquals(JsonToken.STRING, cursor.next());
                assertEquals(JsonException.Kind.BINDING,
                        assertThrows(JsonException.class, () -> cursor.readValue(ReferenceNode.class)).kind());
            }
        }
    }

    private static JsonMapper referenceMapper() {
        return Json.builder().provider("jackson2")
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
                        builder -> builder.addMixIn(ReferenceNode.class, ReferenceMixin.class))
                .build();
    }

    public static class ReferenceNode {
        public String id;
        public ReferenceNode next;
    }

    @JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
    abstract static class ReferenceMixin {
    }

    public record NativeValue(String value) {
    }

    abstract static class NameMixin {
        @JsonProperty("native_name")
        abstract String displayName();
    }
}
