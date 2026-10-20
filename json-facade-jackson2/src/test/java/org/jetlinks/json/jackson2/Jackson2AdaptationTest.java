package org.jetlinks.json.jackson2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jackson2AdaptationTest {
    @Test
    void snapshotPreservesNativeExtensionsAndDoesNotFollowLaterSourceChanges() throws IOException {
        SimpleModule module = new SimpleModule("host");
        module.addSerializer(Label.class, new JsonSerializer<>() {
            @Override
            public void serialize(Label value, JsonGenerator output, SerializerProvider context) throws IOException {
                output.writeString("host:" + value.text());
            }
        });
        ObjectMapper source = new ObjectMapper().registerModule(module)
                .addMixIn(Name.class, NameMixin.class)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setDefaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_EMPTY));
        var facade = Jackson2Provider.adapt(source);
        assertEquals("\"host:v\"", facade.write(new Label("v")));
        assertEquals("{\"external_name\":\"n\"}", facade.write(new Name("n")));
        assertEquals(new Name("n"), facade.read("{\"external_name\":\"n\"}", Name.class));
        assertEquals("{}", facade.write(new Names("", null)));
        assertTrue(facade.options().omitNulls());
        assertEquals(source.writeValueAsString(new Names("n", null)), facade.write(new Names("n", null)));
        source.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        source.clearCaches();
        assertEquals("{\"display_name\":\"n\"}", facade.write(new Names("n", null)));
        assertEquals("{\"DisplayName\":\"n\"}", source.writeValueAsString(new Names("n", null)));
        assertTrue(source.isEnabled(JsonParser.Feature.AUTO_CLOSE_SOURCE));
        assertTrue(source.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET));
        assertFalse(source.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
        assertThrows(JsonException.class, () -> facade.read("{} []", Object.class));
    }

    @Test
    void preservesCustomInclusionRatherThanReducingItToBooleanNullOmission() throws IOException {
        ObjectMapper source = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Value.construct(
                JsonInclude.Include.CUSTOM, JsonInclude.Include.ALWAYS).withValueFilter(DropFilter.class));
        var facade = Jackson2Provider.adapt(source);
        Names value = new Names("drop", "keep");
        assertEquals(source.writeValueAsString(value), facade.write(value));
        assertEquals("{\"otherName\":\"keep\"}", facade.write(value));
        assertFalse(facade.options().omitNulls());
    }

    @Test
    void importsNativeFlagsAndLimitsAndAlignsDepthWithoutChangingSource() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(7)
                        .maxStringLength(13).maxNumberLength(9).build())
                .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(4).build()).build();
        ObjectMapper source = new ObjectMapper(factory)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var facade = Jackson2Provider.adapt(source, 32);
        assertEquals(new JsonOptions(true, true, true, false, true, 4, 13, 9, 32), facade.options());
        assertEquals(7, factory.streamReadConstraints().getMaxNestingDepth());
        assertEquals(4, factory.streamWriteConstraints().getMaxNestingDepth());
        assertThrows(JsonException.class, () -> facade.read("{\"x\":1,\"x\":2}", Object.class));
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class,
                () -> facade.read("[[[[[1]]]]]", Object.class)).kind());
        assertThrows(JsonException.class, () -> facade.read("{\"unknown\":1}", Name.class));
        assertTrue(facade.write(Map.of("a", 1)).contains("\n"));
        ByteArrayOutputStream compact = new ByteArrayOutputStream();
        facade.writeCompact(Map.of("a", 1), compact);
        assertEquals("{\"a\":1}", compact.toString(StandardCharsets.UTF_8));
    }

    @Test
    void neutralTreesRemainBindableInsideHostObjects() {
        var facade = Jackson2Provider.adapt(new ObjectMapper());
        Tree value = new Tree(facade.createObject(Map.of("a", 1)), facade.createArray(List.of(2, 3)));
        Tree result = facade.read(facade.write(value), Tree.class);
        assertEquals(1, result.object().get("a").toJavaValue());
        assertEquals(2, result.array().get(0).toJavaValue());
        assertEquals(1, result.object().get("a").asInteger());
        assertEquals(2, result.array().get(0).asInt());
        assertSame(JsonValue.NULL, facade.read("null", JsonValue.class));
    }

    @Test
    void adaptedTreesUseNativeCoercionAndPreserveHostContextAttributes() {
        ObjectMapper source = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .defaultAttributes(com.fasterxml.jackson.databind.cfg.ContextAttributes.getEmpty()
                        .withSharedAttribute("offset", 30))
                .withCoercionConfig(com.fasterxml.jackson.databind.type.LogicalType.Integer,
                        coercion -> coercion.setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
                                com.fasterxml.jackson.databind.cfg.CoercionAction.Fail))
                .build();
        SimpleModule module = new SimpleModule("context-number");
        module.addDeserializer(ContextNumber.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public ContextNumber deserialize(JsonParser input,
                    com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                return new ContextNumber(input.getIntValue() + (Integer) context.getAttribute("offset"));
            }
        });
        source.registerModule(module);
        var facade = Jackson2Provider.adapt(source);
        assertEquals(42, facade.readTree("12").as(ContextNumber.class).value());
        assertThrows(JsonException.class, () -> facade.readTree("{\"n\":\"12\"}").get("n").asInteger());
        assertEquals(12, facade.read("{\"a\":12}", JsonObject.class).get("a").asInt());
        assertEquals(30, source.getDeserializationConfig().getAttributes().getAttribute("offset"));
        org.junit.jupiter.api.Assertions.assertNull(source.getDeserializationConfig().getAttributes()
                .getAttribute(org.jetlinks.json.internal.JsonTreeBinding.class));
    }

    /** Native context attributes survive the independent facade snapshot. */
    public record ContextNumber(int value) {
    }

    @Test
    void everyCallerOwnedIoRemainsOpenAndIncompleteWriterIsNotRepaired() {
        var facade = Jackson2Provider.adapt(new ObjectMapper());
        Input input = new Input("1");
        assertEquals(1, facade.read(input, JsonType.of(Integer.class)));
        assertFalse(input.closed);
        Input cursorInput = new Input("1");
        try (var reader = facade.reader(cursorInput)) {
            assertEquals(JsonToken.NUMBER, reader.next());
        }
        assertFalse(cursorInput.closed);
        TextInput textInput = new TextInput("1");
        assertEquals(1, facade.read(textInput, JsonType.of(Integer.class)));
        assertFalse(textInput.closed);
        Output output = new Output();
        facade.write(Map.of("a", 1), output);
        assertFalse(output.closed);
        TextOutput textOutput = new TextOutput();
        facade.write(Map.of("a", 1), textOutput);
        assertFalse(textOutput.closed);
        Output incomplete = new Output();
        try (var writer = facade.writer(incomplete)) {
            writer.beginObject();
        }
        assertFalse(incomplete.closed);
        assertEquals("{", incomplete.toString(StandardCharsets.UTF_8));
    }

    @Test
    void invalidSourcesFailAtTheAdaptationBoundary() {
        assertThrows(NullPointerException.class, () -> Jackson2Provider.adapt(null));
        assertThrows(IllegalArgumentException.class, () -> Jackson2Provider.adapt(new ObjectMapper(), 0));
        ObjectMapper nonJson = new ObjectMapper(new OtherFactory());
        assertEquals(JsonException.Kind.CONFIGURATION,
                assertThrows(JsonException.class, () -> Jackson2Provider.adapt(nonJson)).kind());
        JsonException failure = assertThrows(JsonException.class,
                () -> Jackson2Provider.adapt(new UncopyableMapper()));
        assertEquals(JsonException.Kind.CONFIGURATION, failure.kind());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    public record Label(String text) {
    }

    public record Name(String displayName) {
    }

    public record Names(String displayName, String otherName) {
    }

    public record Tree(JsonObject object, JsonArray array) {
    }

    abstract static class NameMixin {
        @JsonProperty("external_name")
        abstract String displayName();
    }

    public static final class DropFilter {
        @Override
        public boolean equals(Object value) {
            return "drop".equals(value);
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }

    private static final class OtherFactory extends JsonFactory {
        private static final long serialVersionUID = 1L;

        @Override
        public String getFormatName() {
            return "OTHER";
        }
    }

    private static final class UncopyableMapper extends ObjectMapper {
        private static final long serialVersionUID = 1L;
    }

    private static final class Input extends ByteArrayInputStream {
        private boolean closed;

        private Input(String value) {
            super(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TextInput extends StringReader {
        private boolean closed;

        private TextInput(String value) {
            super(value);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    private static final class Output extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TextOutput extends StringWriter {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
