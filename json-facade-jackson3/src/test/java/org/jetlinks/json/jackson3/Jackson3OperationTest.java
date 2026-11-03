package org.jetlinks.json.jackson3;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.module.SimpleModule;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonAttribute;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonOptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jackson3OperationTest {
    private static final JsonAttribute<String> MODE = JsonAttribute.of("mode", String.class);

    private static JsonMapper mapper() {
        return Json.builder().provider(new Jackson3Provider()).build();
    }

    @Test
    void nativeImmutableReadersAndWritersArePreparedOnceAndCanBeReused() {
        var calls = new AtomicInteger();
        JsonMapper base = mapper();
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder()
                .nativeReader(ObjectReader.class, reader -> reader.with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY))
                .nativeWriter(ObjectWriter.class, writer -> {
                    calls.incrementAndGet();
                    return writer.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .with(SerializationFeature.INDENT_OUTPUT);
                }).build());
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("z", 1);
        map.put("a", 2);
        for (int i = 0; i < 3; i++) {
            assertEquals(List.of(1), operation.read("1",
                    org.jetlinks.json.JsonType.listOf(org.jetlinks.json.JsonType.of(Integer.class))));
            assertTrue(operation.write(map).indexOf("\"a\"") < operation.write(map).indexOf("\"z\""));
            assertTrue(operation.options().pretty());
            try (var session = operation.readSession(org.jetlinks.json.JsonType.listOf(
                    org.jetlinks.json.JsonType.of(Integer.class)), org.jetlinks.json.JsonReadOptions.of(
                    org.jetlinks.json.JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
                session.feed(java.nio.ByteBuffer.wrap("[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                session.endInput();
                assertEquals(List.of(1), session.read().value());
                assertEquals(List.of(2), session.read().value());
            }
            var output = new ByteArrayOutputStream();
            try (var writer = operation.compactWriter(output)) {
                writer.value(map);
            }
            assertEquals("{\"a\":2,\"z\":1}", output.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(1, calls.get());
        assertEquals("{\"z\":1,\"a\":2}", base.write(map));
        JsonMapper printer = base.withOptions(JsonOperationOptions.builder()
                .nativeWriter(ObjectWriter.class,
                        writer -> writer.with(new tools.jackson.core.util.DefaultPrettyPrinter()))
                .build());
        assertTrue(printer.options().pretty());
        assertTrue(printer.write(map).contains("\n"));
        var compact = new ByteArrayOutputStream();
        printer.writeCompact(map, compact);
        assertEquals("{\"z\":1,\"a\":2}", compact.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void filteredNativeReadersAreRejectedBeforeAnyValueBinding() {
        JsonMapper base = mapper();
        for (String pointer : List.of("", "/a", "/a/0")) {
            assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class,
                    () -> base.withOptions(JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                            reader -> reader.at(pointer)).build())).kind());
            assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class,
                    () -> base.withOptions(JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                            reader -> reader.at(tools.jackson.core.JsonPointer.compile(pointer))).build())).kind());
        }
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                reader -> reader.with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)).build());
        assertEquals(Map.of("a", 1), operation.read("{\"a\":1}", Map.class));
        for (String trailing : List.of("42", "{\"b\":2}", "{\"a\":2}")) {
            String input = "{\"a\":1} " + trailing;
            byte[] bytes = input.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertThrows(JsonException.class, () -> operation.read(input, Object.class));
            assertThrows(JsonException.class, () -> operation.read(bytes, Object.class));
            assertThrows(JsonException.class, () -> operation.read(new java.io.ByteArrayInputStream(bytes), Object.class));
            assertThrows(JsonException.class, () -> operation.read(new java.io.StringReader(input), Object.class));
        }
    }

    @Test
    void preparationDoesNotBindAValueThroughNativeHandlers() {
        var calls = new AtomicInteger();
        var module = new SimpleModule();
        module.addDeserializer(Object.class, new tools.jackson.databind.ValueDeserializer<>() {
            @Override
            public Object deserialize(JsonParser input, tools.jackson.databind.DeserializationContext context) {
                calls.incrementAndGet();
                input.skipChildren();
                return "bound";
            }
        });
        JsonMapper base = Json.builder().provider(new Jackson3Provider())
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, builder -> builder.addModule(module))
                .build();
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().pretty(true).build());
        assertEquals(0, calls.get());
        assertEquals("bound", operation.read("{}", Object.class));
        assertEquals(1, calls.get());
    }

    @Test
    void nativeCallbacksRejectConflictsAndOwnershipBypassesDuringPreparation() {
        JsonMapper base = mapper();
        List<JsonOperationOptions> invalid = List.of(
                JsonOperationOptions.builder().pretty(true).nativeWriter(ObjectWriter.class,
                        writer -> writer.without(SerializationFeature.INDENT_OUTPUT)).build(),
                JsonOperationOptions.builder().pretty(false).nativeWriter(ObjectWriter.class,
                        writer -> writer.with(SerializationFeature.INDENT_OUTPUT)).build(),
                JsonOperationOptions.builder().losslessNumbers(true).nativeReader(ObjectReader.class,
                        reader -> reader.without(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).build(),
                JsonOperationOptions.builder().failUnknownProperties(true).nativeReader(ObjectReader.class,
                        reader -> reader.without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).build(),
                JsonOperationOptions.builder().failDuplicateKeys(true).nativeReader(ObjectReader.class,
                        reader -> reader.without(StreamReadFeature.STRICT_DUPLICATE_DETECTION)).build(),
                JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                        reader -> reader.without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).build(),
                JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                        reader -> reader.with(StreamReadFeature.AUTO_CLOSE_SOURCE)).build(),
                JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                        reader -> reader.with(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)).build(),
                JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                        reader -> reader.forType(String.class)).build(),
                JsonOperationOptions.builder().nativeWriter(ObjectWriter.class,
                        writer -> writer.with(StreamWriteFeature.AUTO_CLOSE_TARGET)).build(),
                JsonOperationOptions.builder().nativeWriter(ObjectWriter.class,
                        writer -> writer.with(StreamWriteFeature.AUTO_CLOSE_CONTENT)).build(),
                JsonOperationOptions.builder().nativeWriter(ObjectWriter.class,
                        writer -> writer.with(SerializationFeature.CLOSE_CLOSEABLE)).build(),
                JsonOperationOptions.builder().pretty(false).nativeWriter(ObjectWriter.class,
                        writer -> writer.with(new tools.jackson.core.util.DefaultPrettyPrinter())).build(),
                JsonOperationOptions.builder().pretty(true).nativeWriter(ObjectWriter.class,
                        writer -> writer.without(SerializationFeature.INDENT_OUTPUT)
                                .with((tools.jackson.core.PrettyPrinter) null)).build());
        for (JsonOperationOptions options : invalid) {
            assertEquals(JsonException.Kind.CONFIGURATION,
                    assertThrows(JsonException.class, () -> base.withOptions(options),
                            "invalid options index " + invalid.indexOf(options)).kind());
        }
        var calls = new AtomicInteger();
        var mismatch = JsonOperationOptions.builder()
                .nativeReader(ObjectReader.class, reader -> {
                    calls.incrementAndGet();
                    return reader;
                })
                .nativeWriter(String.class, value -> value).build();
        assertThrows(JsonException.class, () -> base.withOptions(mismatch));
        assertEquals(0, calls.get());
        assertThrows(JsonException.class, () -> base.withOptions(JsonOperationOptions.builder()
                .nativeWriter(ObjectWriter.class, writer -> null).build()));
        assertThrows(JsonException.class, () -> base.withOptions(JsonOperationOptions.builder()
                .nativeReader(ObjectReader.class, reader -> { throw new IllegalStateException("callback"); }).build()));
        assertThrows(JsonException.class, () -> base.withOptions(JsonOperationOptions.builder()
                .nativeWriter(ObjectWriter.class,
                        writer -> tools.jackson.databind.json.JsonMapper.builder().build().writer()).build()));

    }

    @Test
    void nullDefaultsRespectNativeAnnotationsAndKeepOriginalCachesIsolated() {
        JsonMapper base = Json.builder().provider(new Jackson3Provider())
                .options(JsonOptions.DEFAULT.withOmitNulls(true)).build();
        assertEquals("{}", base.write(new Plain(null)));
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().omitNulls(false).build());
        assertEquals("{\"value\":null}", operation.write(new Plain(null)));
        assertEquals("{}", base.write(new Plain(null)));
        assertEquals("{\"value\":null}", base.write(new Included(null)));
        assertEquals("{}", operation.write(new Omitted(null)));
    }

    @Test
    void typedAttributesAreAlsoAvailableToNativeBindingHandlers() {
        SimpleModule module = new SimpleModule("native-attributes");
        module.addSerializer(NativeWrapper.class, new tools.jackson.databind.ValueSerializer<>() {
            @Override
            public void serialize(NativeWrapper value, JsonGenerator output,
                                  tools.jackson.databind.SerializationContext context) {
                output.writePOJO(value.item());
            }
        });
        module.addSerializer(NativeValue.class, new tools.jackson.databind.ValueSerializer<>() {
            @Override
            public void serialize(NativeValue value, JsonGenerator output,
                    tools.jackson.databind.SerializationContext context) {
                output.writeString(context.getAttribute(MODE) + ":" + value.value());
            }
        });
        module.addDeserializer(NativeValue.class, new tools.jackson.databind.ValueDeserializer<>() {
            @Override
            public NativeValue deserialize(JsonParser input, tools.jackson.databind.DeserializationContext context) {
                return new NativeValue(context.getAttribute(MODE) + ":" + input.getString());
            }
        });
        JsonMapper base = Json.builder().provider(new Jackson3Provider())
                .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, builder -> builder.addModule(module))
                .build();
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().attribute(MODE, "custom").build());
        assertEquals("\"custom:x\"", operation.write(new NativeValue("x")));
        assertEquals(new NativeValue("custom:x"), operation.read("\"x\"", NativeValue.class));
        assertEquals("\"null:x\"", base.write(new NativeValue("x")));
        assertEquals(new NativeValue("custom:custom:x"), operation.convert(new NativeValue("x"), NativeValue.class));
        assertEquals("custom:x", operation.convert(new NativeWrapper(new NativeValue("x")), String.class));
    }

    public record NativeValue(String value) { }
    public record NativeWrapper(NativeValue item) { }
    public record Plain(String value) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Included(String value) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Omitted(String value) { }
}
