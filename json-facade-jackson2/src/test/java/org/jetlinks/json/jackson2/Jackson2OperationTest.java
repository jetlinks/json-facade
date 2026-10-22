package org.jetlinks.json.jackson2;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
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

class Jackson2OperationTest {
    private static final JsonAttribute<String> MODE = JsonAttribute.of("mode", String.class);

    private static JsonMapper mapper() {
        return Json.builder().provider(new Jackson2Provider()).build();
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
                        writer -> writer.with(new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()))
                .build());
        assertTrue(printer.options().pretty());
        assertTrue(printer.write(map).contains("\n"));
        var compact = new ByteArrayOutputStream();
        printer.writeCompact(map, compact);
        assertEquals("{\"z\":1,\"a\":2}", compact.toString(java.nio.charset.StandardCharsets.UTF_8));
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
                        writer -> writer.with(new com.fasterxml.jackson.core.util.DefaultPrettyPrinter())).build(),
                JsonOperationOptions.builder().pretty(true).nativeWriter(ObjectWriter.class,
                        writer -> writer.with((com.fasterxml.jackson.core.PrettyPrinter) null)).build());
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
                .nativeWriter(ObjectWriter.class, writer -> writer.with(new com.fasterxml.jackson.core.JsonFactory()))
                .build()));
    }

    @Test
    void nullDefaultsRespectNativeAnnotationsAndKeepOriginalCachesIsolated() {
        JsonMapper base = Json.builder().provider(new Jackson2Provider())
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
        module.addSerializer(NativeValue.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(NativeValue value, JsonGenerator output,
                    com.fasterxml.jackson.databind.SerializerProvider context) throws java.io.IOException {
                output.writeString(context.getAttribute(MODE) + ":" + value.value());
            }
        });
        module.addDeserializer(NativeValue.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public NativeValue deserialize(JsonParser input, com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                return new NativeValue(context.getAttribute(MODE) + ":" + input.getText());
            }
        });
        JsonMapper base = Json.builder().provider(new Jackson2Provider())
                .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, builder -> builder.addModule(module))
                .build();
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().attribute(MODE, "custom").build());
        assertEquals("\"custom:x\"", operation.write(new NativeValue("x")));
        assertEquals(new NativeValue("custom:x"), operation.read("\"x\"", NativeValue.class));
        assertEquals("\"null:x\"", base.write(new NativeValue("x")));
        assertEquals(new NativeValue("custom:custom:x"), operation.convert(new NativeValue("x"), NativeValue.class));
    }

    public record NativeValue(String value) { }
    public record Plain(String value) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Included(String value) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Omitted(String value) { }
}
