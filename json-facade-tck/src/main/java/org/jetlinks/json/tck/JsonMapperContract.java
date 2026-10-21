package org.jetlinks.json.tck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reusable black-box contract; implementations run these tests against their actual engine.
 * Uses no engine mocks. Test payloads are deliberately bounded.
 * @since 1.0.0
 */
public abstract class JsonMapperContract extends JsonTreeContract {
    private static final JsonType<List<Map<String, BigDecimal>>> DECIMAL_ROWS = new JsonType<>() { };

    protected abstract JsonMapper mapper(JsonOptions options, JsonModule... modules);

    protected final JsonMapper mapper() {
        return mapper(JsonOptions.DEFAULT);
    }

    @Test
    void treeFactoriesAndExportsIsolateNestedMutableContainers() {
        JsonMapper json = mapper();
        List<Object> original = new ArrayList<>(List.of(1, 2));
        JsonObject object = json.createObject(Map.of("rows", original));
        original.add(3);
        assertEquals(2, object.get("rows").size());
        object.get("rows").add(null);
        assertEquals(List.of(1, 2, 3), original);
        Map<?, ?> exported = (Map<?, ?>) object.toJavaValue();
        ((List<?>) exported.get("rows")).clear();
        assertEquals(3, object.get("rows").size());
        JsonArray array = json.createArray(List.of(object, new int[]{4, 5}));
        object.put("later", true);
        assertTrue(array.get(0).get("later").isMissing());
        assertEquals(5, array.get(1).get(1).toJavaValue());
        assertEquals(array.toJavaValue(), json.read(json.write(array), JsonArray.class).toJavaValue());
        assertEquals(object.toJavaValue(), json.read(json.write(object), JsonObject.class).toJavaValue());
        assertEquals(Map.of("tree", object.toJavaValue()),
                json.read(json.write(Map.of("tree", object)), Map.class));
        assertInstanceOf(JsonArray.class, json.createArray());
        assertThrows(IllegalArgumentException.class, () -> JsonValue.of(Map.of(1, "value")));
        assertThrows(IllegalArgumentException.class, () -> json.createArray().add(new Item(1)));
        assertThrows(IllegalStateException.class, () -> json.createArray().add(JsonValue.MISSING));
        assertEquals(JsonException.Kind.BINDING,
                assertThrows(JsonException.class, () -> json.read("[]", JsonObject.class)).kind());
    }

    @Test
    void reusableCompactWriterRetainsAdaptersAndValidatesNumericTokens() {
        JsonMapper json = mapper(JsonOptions.DEFAULT, new LabelsModule());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonWriter writer = json.compactWriter(bytes)) {
            writer.beginArray();
            writer.value(new Label("a"));
            writer.value(json.createObject(Map.of("n", 1)));
            writer.number("1.2300E+2");
            writer.number("-0");
            writer.number("0e+9");
            writer.number("-12.50e-2");
            writer.endArray();
            writer.flush();
        }
        assertEquals("[\"label:a\",{\"n\":1},1.2300E+2,-0,0e+9,-12.50e-2]", bytes.toString(StandardCharsets.UTF_8));
        for (String invalid : List.of("", "-", "01", "NaN", "1,2", "1]", "1e", "+1", ".1", "1.",
                "1e+", "1E-", "١", "1.١")) {
            try (JsonWriter writer = json.compactWriter(new ByteArrayOutputStream())) {
                assertThrows(JsonException.class, () -> writer.number(invalid));
            }
        }
    }

    @Test
    void bindsNestedGenericTypesAndPreservesPrecision() {
        JsonMapper json = mapper();
        List<Map<String, BigDecimal>> values = json.read("[{\"n\":1.0000000000000000001}]",
                DECIMAL_ROWS);
        assertEquals(new BigDecimal("1.0000000000000000001"), values.get(0).get("n"));
        Map<?, ?> loose = json.read("{\"n\":1.0000000000000000001,\"i\":1234567890123456789012345}", Map.class);
        assertInstanceOf(BigDecimal.class, loose.get("n"));
        assertInstanceOf(BigInteger.class, loose.get("i"));
        assertEquals(List.of(1, 2), json.read("[1,2]", JsonType.listOf(JsonType.of(Integer.class))));
        assertEquals(Map.of("x", 3), json.read("{\"x\":3}", JsonType.mapOf(JsonType.of(String.class), JsonType.of(Integer.class))));
        assertEquals(7, json.convert(Map.of("value", 7), JsonType.of(Item.class)).value);
        assertEquals(4, json.read("{\"value\":4}".getBytes(StandardCharsets.UTF_8), Item.class).value);
    }

    @Test
    void nullsTrailingContentAndUnknownFieldsHaveExplicitSemantics() {
        JsonMapper json = mapper();
        assertNull(json.read("null", Object.class));
        assertSame(JsonValue.NULL, json.read("null", JsonValue.class));
        assertThrows(JsonException.class, () -> json.read("", Object.class));
        assertThrows(JsonException.class, () -> json.read("{} []", Object.class));
        assertEquals(2, json.read("{\"value\":2,\"other\":1}", Item.class).value);
        JsonOptions strict = JsonOptions.DEFAULT.withFailUnknownProperties(true).withFailDuplicateKeys(true);
        JsonMapper strictMapper = mapper(strict);
        assertThrows(JsonException.class, () -> strictMapper.read("{\"value\":2,\"other\":1}", Item.class));
        assertThrows(JsonException.class, () -> strictMapper.read("{\"x\":1,\"x\":2}", Object.class));
        assertEquals(2, json.read("{\"x\":1,\"x\":2}", Map.class).get("x"));
    }

    @Test
    void explicitOptionsControlNumbersNullsAndPrettyPrinting() {
        JsonOptions options = JsonOptions.DEFAULT.withLosslessNumbers(false).withOmitNulls(true).withPretty(true);
        JsonMapper json = mapper(options);
        assertSame(options, json.options());
        assertInstanceOf(Double.class, json.read("1.25", Object.class));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("present", 1);
        values.put("absent", null);
        String output = json.write(values);
        assertFalse(output.contains("absent"));
        assertTrue(output.contains("\n"));
        assertEquals("[null]", mapper().write(java.util.Collections.singletonList(null)));
    }

    @Test
    void compactOutputUsesBindingPoliciesWithoutChangingTheSharedPrettyMapper() {
        JsonOptions options = new JsonOptions(true, false, false, true, true, 256, 10000, 1000, 10000);
        JsonMapper json = mapper(options, new LabelsModule());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("label", new Label("a"));
        values.put("tree", json.toTree(Map.of("nested", List.of(new Label("b")))));
        values.put("null", null);
        TrackedOutput target = new TrackedOutput();
        json.writeCompact(values, target);
        assertEquals("{\"label\":\"label:a\",\"tree\":{\"nested\":[\"label:b\"]}}",
                target.toString(StandardCharsets.UTF_8));
        assertFalse(target.closed);
        assertTrue(json.write(values).contains("\n"));
    }

    @Test
    void incrementalReadsDoNotApplyNullOmissionDuringBinding() {
        JsonOptions options = new JsonOptions(true, false, false, true, false, 256, 10000, 1000, 10000);
        JsonMapper json = mapper(options);
        byte[] input = "{\"value\":null}".getBytes(StandardCharsets.UTF_8);
        assertNull(json.read(input, DefaultItem.class).value);
        for (int split = 0; split <= input.length; split++) {
            DefaultItem item = read(json, input, split, JsonType.of(DefaultItem.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE)).get(0);
            assertNull(item.value);
        }
    }

    @Test
    void incrementalNumbersRetainNativeTypesPrecisionAndInputText() {
        for (boolean lossless : List.of(false, true)) {
            JsonOptions options = new JsonOptions(lossless, false, false, false, false, 256, 10000, 1000, 10000);
            JsonMapper json = mapper(options);
            for (String number : List.of("1.25", "1e2", "1.250", "1e-50", "9223372036854775808")) {
                byte[] input = number.getBytes(StandardCharsets.UTF_8);
                for (int split = 0; split <= input.length; split++) {
                    JsonReadOptions framing = JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE);
                    Object expected = json.read(input, Object.class);
                    Object actual = read(json, input, split, JsonType.of(Object.class), framing).get(0);
                    assertEquals(expected.getClass(), actual.getClass(), number);
                    assertEquals(expected, actual, number);
                    assertEquals(json.read(input, String.class),
                            read(json, input, split, JsonType.of(String.class), framing).get(0), number);
                }
            }
        }
    }

    @Test
    void incrementalIntegerNegativeZeroFollowsNativeTokenizerNormalization() {
        JsonMapper json = mapper();
        byte[] input = "-0".getBytes(StandardCharsets.UTF_8);
        assertEquals("-0", json.read(input, String.class));
        for (int split = 0; split <= input.length; split++) {
            JsonReadOptions framing = JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE);
            assertEquals(List.of(0), read(json, input, split, JsonType.of(Object.class), framing));
            // Both native nonblocking tokenizers normalize integer -0 before exposing token text.
            assertEquals(List.of("0"), read(json, input, split, JsonType.of(String.class), framing));
        }
    }

    @Test
    void incrementalAdaptersReceiveOriginalDuplicateFieldsAndNumericText() {
        JsonMapper json = mapper(JsonOptions.DEFAULT, new TokenLogModule());
        byte[] input = "{\"x\":1,\"x\":2,\"n\":1e2}".getBytes(StandardCharsets.UTF_8);
        TokenLog expected = new TokenLog("FIELD_NAME:x;NUMBER:1;FIELD_NAME:x;NUMBER:2;FIELD_NAME:n;NUMBER:1e2;");
        assertEquals(expected, json.read(input, TokenLog.class));
        for (int split = 0; split <= input.length; split++) {
            assertEquals(List.of(expected), read(json, input, split, JsonType.of(TokenLog.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE)));
        }
        JsonMapper labels = mapper(JsonOptions.DEFAULT, new LabelsModule());
        byte[] nested = "[\"label:a\",\"label:b\"]".getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(List.of(new Label("a"), new Label("b"))), read(labels, nested, 3,
                JsonType.listOf(JsonType.of(Label.class)), JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE)));
    }

    @Test
    void incrementalCancellationStopsBeforeTheRemainderIsParsed() {
        for (boolean direct : List.of(false, true)) {
            AtomicInteger checks = new AtomicInteger();
            try (JsonReadSession<Object> session = mapper().readSession(JsonType.of(Object.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE))) {
                session.feed(ByteBuffer.wrap("{\"a\":[1,INVALID".getBytes(StandardCharsets.UTF_8)));
                JsonReadSession.State state = direct
                        ? session.read((next, value) -> next, () -> checks.incrementAndGet() == 4)
                        : session.read(() -> checks.incrementAndGet() == 4).state();
                assertEquals(JsonReadSession.State.END, state);
                assertEquals(4, checks.get());
            }
        }
    }

    @Test
    void genericAndPullReadsShareNullChunkBoundariesAndEofState() {
        JsonReadSession.Receiver<Number, String> receiver = (state, value) -> state + ":" + value;
        try (JsonReadSession<Integer> session = mapper().readSession(JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
            assertEquals("NEED_INPUT:null", session.read(receiver, () -> false));
            session.feed(ByteBuffer.wrap("[null,1".getBytes(StandardCharsets.UTF_8)));
            assertEquals("VALUE:null", session.read(receiver, () -> false));
            assertEquals(JsonReadSession.State.NEED_INPUT, session.read().state());
            session.feed(ByteBuffer.wrap(",2]".getBytes(StandardCharsets.UTF_8)));
            assertEquals("VALUE:1", session.read(receiver, () -> false));
            assertEquals(2, session.read().value());
            assertEquals("NEED_INPUT:null", session.read(receiver, () -> false));
            session.endInput();
            assertEquals("END:null", session.read(receiver, () -> false));
            assertNull(session.read((state, value) -> null, () -> false));
            assertThrows(NullPointerException.class, () -> session.read(null, () -> false));
            assertThrows(NullPointerException.class, () -> session.read(receiver, null));
        }
    }

    @Test
    void genericEventsRemainIndependentAndReceiverFailuresKeepTheirIdentity() {
        List<JsonEvent> events = new ArrayList<>();
        JsonReadSession.Receiver<JsonEvent, JsonReadSession.State> receiver = (state, value) -> {
            if (state == JsonReadSession.State.VALUE) {
                events.add(value);
            } else {
                assertNull(value);
            }
            return state;
        };
        try (JsonReadSession<JsonEvent> cancelled = mapper().eventSession()) {
            assertEquals(JsonReadSession.State.END, cancelled.read(receiver, () -> true));
        }
        try (JsonReadSession<JsonEvent> session = mapper().eventSession()) {
            session.feed(ByteBuffer.wrap("[1,\"x\"]".getBytes(StandardCharsets.UTF_8)));
            session.endInput();
            while (session.read(receiver, () -> false) == JsonReadSession.State.VALUE) {
                // Retain all events across subsequent parser operations and close.
            }
        }
        assertEquals(List.of(JsonEvent.START_ARRAY, JsonEvent.number("1"), JsonEvent.string("x"),
                JsonEvent.END_ARRAY), events);
        IllegalStateException failure = new IllegalStateException("receiver");
        try (JsonReadSession<JsonEvent> session = mapper().eventSession()) {
            session.feed(ByteBuffer.wrap("{}".getBytes(StandardCharsets.UTF_8)));
            assertSame(failure, assertThrows(IllegalStateException.class, () -> session.read((state, value) -> {
                throw failure;
            }, () -> false)));
        }
    }

    @Test
    void stringBytesAndCallerOwnedStreamsAreEquivalent() throws IOException {
        JsonMapper json = mapper();
        String input = "{\"value\":9}";
        TrackedInput source = new TrackedInput(input.getBytes(StandardCharsets.UTF_8));
        assertEquals(9, json.read(source, Item.class).value);
        assertFalse(source.closed);
        TrackedOutput target = new TrackedOutput();
        json.write(new Item(9), target);
        assertFalse(target.closed);
        assertEquals(json.write(new Item(9)), target.toString(StandardCharsets.UTF_8));
        StringReader reader = new StringReader(input);
        assertEquals(9, json.read(reader, Item.class).value);
        StringWriter writer = new StringWriter();
        json.write(new Item(9), writer);
        assertEquals(input, writer.toString());
        assertArrayEquals(input.getBytes(StandardCharsets.UTF_8), json.writeBytes(new Item(9)));
        try (BrokenOutput broken = new BrokenOutput(); BrokenInput brokenSource = new BrokenInput()) {
            assertEquals(JsonException.Kind.IO,
                    assertThrows(JsonException.class, () -> json.write(new Item(1), broken)).kind());
            assertEquals(JsonException.Kind.IO,
                    assertThrows(JsonException.class, () -> json.read(brokenSource, Item.class)).kind());
        }
    }

    @Test
    void convenienceInputsPreserveGenericNullPrimitiveAndAdapterBinding() {
        JsonMapper json = mapper();
        String document = "[{\"n\":1.0000000000000000001}]";
        var expected = List.of(Map.of("n", new BigDecimal("1.0000000000000000001")));
        for (var read : inputReads(json, document, DECIMAL_ROWS)) {
            assertEquals(expected, read.get());
        }
        for (var read : inputReads(json, "null", JsonType.of(Object.class))) {
            assertNull(read.get());
        }
        for (var read : inputReads(json, "7", JsonType.of(int.class))) {
            assertEquals(7, read.get());
        }
        var primitiveNullReads = inputReads(json, "null", JsonType.of(int.class));
        Integer primitiveNull;
        try {
            primitiveNull = primitiveNullReads.get(0).get();
        } catch (JsonException error) {
            assertInputFailure(json, "null", JsonType.of(int.class), error.kind());
            primitiveNull = null;
        }
        if (primitiveNull != null) {
            for (var read : primitiveNullReads) {
                assertEquals(primitiveNull, read.get());
            }
        }
        for (var read : inputReads(json, "{\"text\":\"设备😀\"}", JsonType.of(Object.class))) {
            assertEquals(Map.of("text", "设备😀"), read.get());
        }
        JsonMapper adapted = mapper(JsonOptions.DEFAULT, new LabelsModule());
        JsonType<List<Label>> labels = JsonType.listOf(JsonType.of(Label.class));
        for (var read : inputReads(adapted, "[\"label:x\"]", labels)) {
            assertEquals(List.of(new Label("x")), read.get());
        }
        byte[] label = "\"label:x\"".getBytes(StandardCharsets.UTF_8);
        assertEquals(new Label("x"), adapted.read(new ByteArrayInputStream(label), Label.class));
        assertEquals(new Label("x"), adapted.read(new StringReader("\"label:x\""), Label.class));
        assertEquals(new Label("x"), adapted.convert("label:x", Label.class));
        try (JsonReader reader = adapted.reader(new ByteArrayInputStream(label))) {
            assertEquals(JsonToken.STRING, reader.next());
            assertEquals(new Label("x"), reader.readValue(Label.class));
            assertNull(reader.next());
        }
    }

    @Test
    void convenienceInputsPreserveStructuralPoliciesLimitsAndBindingPaths() {
        JsonMapper json = mapper();
        for (String invalid : List.of("", " ", "{", "{} {}", "null true", "[] garbage")) {
            assertInputFailure(json, invalid, JsonType.of(Object.class), null);
        }
        JsonType<List<Item>> items = JsonType.listOf(JsonType.of(Item.class));
        assertInputFailure(json, "[{\"value\":\"no\"}]", items, JsonException.Kind.BINDING);
        JsonMapper strict = mapper(new JsonOptions(true, true, true, false, false, 2, 4, 4, 1024));
        assertInputFailure(strict, "{\"n\":1,\"n\":2}", JsonType.of(Object.class), JsonException.Kind.SYNTAX);
        assertInputFailure(strict, "{\"other\":1}", JsonType.of(Item.class), JsonException.Kind.BINDING);
        for (String limited : List.of("[[[0]]]", "\"12345\"", "12345")) {
            assertInputFailure(strict, limited, JsonType.of(Object.class), JsonException.Kind.LIMIT);
        }
    }

    private static <T> List<Supplier<T>> inputReads(JsonMapper json, String document, JsonType<T> type) {
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        return List.of(() -> json.read(new ByteArrayInputStream(bytes), type),
                () -> json.read(new StringReader(document), type),
                () -> json.read(bytes, type), () -> json.read(document, type));
    }

    private static <T> void assertInputFailure(JsonMapper json, String document, JsonType<T> type,
                                              JsonException.Kind kind) {
        var reads = inputReads(json, document, type);
        JsonException reference = assertThrows(JsonException.class, () -> reads.get(0).get());
        if (kind != null) {
            assertEquals(kind, reference.kind());
        }
        for (var read : reads) {
            JsonException error = assertThrows(JsonException.class, read::get);
            assertEquals(reference.kind(), error.kind());
            assertEquals(reference.pointer(), error.pointer());
            assertEquals(reference.displayPath(), error.displayPath());
        }
    }

    @Test
    void convenienceOutputPreservesUtf8RepresentationConfigurationAndOwnership() {
        JsonOptions pretty = new JsonOptions(true, false, false, true, true,
                256, 8 * 1024 * 1024, 1000, 8 * 1024 * 1024);
        for (JsonOptions options : List.of(JsonOptions.DEFAULT, pretty)) {
            JsonMapper json = mapper(options, new LabelsModule());
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("null", null);
            fields.put("label", new Label("中\ud83d\ude00"));
            Object[] values = {null, "plain", "中\ud83d\ude00", "x\ud800", "x\udc00",
                    "\\\"\b\f\n\r\t", "设备\ud83d\ude00".repeat(3000), new BigDecimal("1.2300"),
                    Double.NaN, fields, JsonValue.object().put("items", JsonValue.array().add(1).add(null))};
            for (Object value : values) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                json.write(value, output);
                byte[] expected = output.toByteArray();
                byte[] actual = json.writeBytes(value);
                assertArrayEquals(expected, actual);
                assertEquals(new String(expected, StandardCharsets.UTF_8), json.write(value));
                json.writeBytes(new Item(99));
                assertArrayEquals(expected, actual);
                actual[0] ^= 1;
                assertArrayEquals(expected, json.writeBytes(value));
            }
        }
    }

    @Test
    void convenienceOutputPreservesNativeBindingFailurePaths() {
        JsonMapper json = mapper();
        List<FailingItem> value = List.of(new FailingItem());
        JsonException stream = assertThrows(JsonException.class, () -> json.write(value, new ByteArrayOutputStream()));
        assertEquals(JsonException.Kind.BINDING, stream.kind());
        assertEquals("/0/value", stream.pointer().toString());
        JsonException bytes = assertThrows(JsonException.class, () -> json.writeBytes(value));
        JsonException text = assertThrows(JsonException.class, () -> json.write(value));
        assertEquals(stream.kind(), bytes.kind());
        assertEquals(stream.pointer(), bytes.pointer());
        assertEquals(stream.kind(), text.kind());
        assertEquals(stream.pointer(), text.pointer());
    }

    @Test
    void neutralTreeRoundTripsWithoutEngineTypes() {
        JsonMapper json = mapper();
        JsonValue tree = JsonValue.object().put("n", null).put("rows", JsonValue.array().add(1).add("中"));
        assertTrue(tree.get("n").isNull());
        assertTrue(tree.get("absent").isMissing());
        assertEquals("中", tree.at(JsonPointer.of("/rows/1")).toJavaValue());
        assertEquals(tree.toJavaValue(), json.readTree(json.write(tree)).toJavaValue());
        assertEquals(Map.of("value", 8), json.toTree(new Item(8)).toJavaValue());
        assertEquals(3, json.convert(JsonValue.object().put("value", 3), Item.class).value);
        assertEquals(Map.of("tree", Map.of("n", 1)),
                json.read(json.write(Map.of("tree", JsonValue.object().put("n", 1))), Map.class));
    }

    @Test
    void cursorBindsCurrentValueAndSkipsUnrelatedContainers() {
        JsonMapper json = mapper();
        TrackedInput input = new TrackedInput("{\"ignored\":[1,{}],\"item\":{\"value\":5}}".getBytes(StandardCharsets.UTF_8));
        try (JsonReader reader = json.reader(input)) {
            assertNull(reader.current());
            assertEquals(JsonToken.START_OBJECT, reader.next());
            assertEquals(JsonToken.FIELD_NAME, reader.next());
            assertEquals("ignored", reader.text());
            assertEquals(JsonToken.START_ARRAY, reader.next());
            reader.skipValue();
            assertEquals(JsonToken.FIELD_NAME, reader.next());
            assertEquals("item", reader.text());
            assertEquals(JsonToken.START_OBJECT, reader.next());
            assertEquals(5, reader.readValue(Item.class).value);
            assertEquals(JsonToken.END_OBJECT, reader.next());
            assertNull(reader.next());
        }
        assertFalse(input.closed);
    }

    @Test
    void tokenWriterPreservesExplicitNullAndDoesNotRepairBrokenDocuments() {
        TrackedOutput output = new TrackedOutput();
        try (JsonWriter writer = mapper().writer(output)) {
            writer.beginObject();
            writer.name("rows");
            writer.beginArray();
            writer.value(null);
            writer.value(new BigDecimal("1.25"));
            writer.endArray();
            writer.endObject();
            writer.flush();
        }
        assertFalse(output.closed);
        assertEquals("{\"rows\":[null,1.25]}", output.toString(StandardCharsets.UTF_8));
        ByteArrayOutputStream broken = new ByteArrayOutputStream();
        try (JsonWriter writer = mapper().writer(broken)) {
            writer.beginArray();
            writer.value(1);
        }
        assertEquals("[1", broken.toString(StandardCharsets.UTF_8));
    }

    @Test
    void neutralAdaptersWorkNestedAndRejectDuplicateRegistration() {
        JsonModule module = new LabelsModule();
        JsonMapper json = mapper(JsonOptions.DEFAULT, module);
        assertEquals("\"label:a\"", json.write(new Label("a")));
        assertEquals(new Label("b"), json.read("\"label:b\"", Label.class));
        assertEquals(List.of(new Label("c")), json.read("[\"label:c\"]", JsonType.listOf(JsonType.of(Label.class))));
        assertThrows(JsonException.class, () -> mapper(JsonOptions.DEFAULT, new DuplicateLabelsModule()));
    }

    @Test
    void neutralAdaptersMatchExactClassesInsteadOfSilentlyApplyingToSubclasses() {
        JsonMapper json = mapper(JsonOptions.DEFAULT, new ExactModule());
        assertEquals("3", json.write(new BaseItem(3)));
        assertEquals(4, json.read("4", BaseItem.class).value);
        DetailedItem child = new DetailedItem();
        child.value = 5;
        child.extra = 6;
        assertEquals(Map.of("value", 5, "extra", 6), json.read(json.write(child), Map.class));
        DetailedItem restored = json.read(json.write(child), DetailedItem.class);
        assertEquals(child.extra, restored.extra);
        assertEquals("\"text\"", json.write("text"));
        assertEquals("text", json.read("\"text\"", CharSequence.class).toString());
    }

    @Test
    void allFramingsWorkAtEveryUtf8SplitAndRejectIncompleteInput() {
        for (JsonReadOptions.Mode mode : JsonReadOptions.Mode.values()) {
            String input = switch (mode) {
                case SINGLE_VALUE -> "{\"value\":\"中\\\"文\"}";
                case ARRAY_ELEMENTS -> "[{\"value\":\"中\\\"文\"},{\"value\":\"末\"}]";
                case NDJSON -> "\r\n{\"value\":\"中\\\"文\"}\r\n{\"value\":\"末\"}";
                case ROOT_VALUES -> "{\"value\":\"中\\\"文\"} {\"value\":\"末\"}";
            };
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            for (int split = 0; split <= bytes.length; split++) {
                List<Map<String, Object>> values = read(mapper(), bytes, split,
                        JsonType.mapOf(JsonType.of(String.class), JsonType.of(Object.class)), JsonReadOptions.of(mode));
                assertEquals(mode == JsonReadOptions.Mode.SINGLE_VALUE ? 1 : 2, values.size(), mode + ":" + split);
                assertEquals("中\"文", values.get(0).get("value"));
            }
        }
        for (String input : List.of("[1", "{\"a\":", "\"unfinished", "1e", "[\"中")) {
            assertThrows(JsonException.class, () -> read(mapper(), input, JsonReadOptions.Mode.SINGLE_VALUE));
        }
        byte[] invalidUtf8 = new byte[]{'"', (byte) 0xe4, (byte) 0xb8};
        assertThrows(JsonException.class, () -> read(mapper(), invalidUtf8, 1,
                JsonType.of(Object.class), JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE)));
    }

    @Test
    void scalarsAndContainerRecordsRetainTheirBoundaries() {
        assertEquals(List.of(1, 2, 3), read(mapper(), "1\n2\r\n3", JsonReadOptions.Mode.NDJSON));
        assertEquals(List.of(true, false), read(mapper(), "true false", JsonReadOptions.Mode.ROOT_VALUES));
        assertEquals(List.of(List.of(1, 2), List.of(3)), read(mapper(), "[[1,2],[3]]", JsonReadOptions.Mode.ARRAY_ELEMENTS));
        assertEquals(List.of(), read(mapper(), "[]", JsonReadOptions.Mode.ARRAY_ELEMENTS));
        assertEquals(List.of(), read(mapper(), "", JsonReadOptions.Mode.NDJSON));
        assertEquals(List.of(), read(mapper(), "", JsonReadOptions.Mode.ROOT_VALUES));
        assertEquals(java.util.Collections.singletonList(null), read(mapper(), "null", JsonReadOptions.Mode.SINGLE_VALUE));
        assertEquals(List.of(new BigDecimal("1.250")), read(mapper(), "1.250", JsonReadOptions.Mode.SINGLE_VALUE));
    }

    @Test
    void malformedFramesFailInsteadOfGuessingOrDroppingRecords() {
        for (String input : List.of("", "{}", "1", "[1] [2]")) {
            assertThrows(JsonException.class, () -> read(mapper(), input, JsonReadOptions.Mode.ARRAY_ELEMENTS));
        }
        for (String input : List.of("", "{} {}", "[] []")) {
            assertThrows(JsonException.class, () -> read(mapper(), input, JsonReadOptions.Mode.SINGLE_VALUE));
        }
        for (String input : List.of("1 2\n", "{\n\"x\":1\n}\n", "{}{}", "1\r2", "{}\r")) {
            assertThrows(JsonException.class, () -> read(mapper(), input, JsonReadOptions.Mode.NDJSON), input);
        }
        assertThrows(JsonException.class, () -> read(mapper(), "{}{}", JsonReadOptions.Mode.ROOT_VALUES));
    }

    @Test
    void selectionSkipsSubtreesAndEmitsBeforeTheDocumentEnds() {
        JsonReadOptions options = JsonReadOptions.selectElements(JsonPointer.of("/data/rows")).withMaxValueBytes(1000);
        try (JsonReadSession<Integer> session = mapper().readSession(Integer.class, options)) {
            session.feed(ByteBuffer.wrap("{\"skip\":{\"x\":[1,2]},\"data\":{\"rows\":[7,".getBytes(StandardCharsets.UTF_8)));
            assertEquals(7, session.read().value());
            assertEquals(JsonReadSession.State.NEED_INPUT, session.read().state());
            session.feed(ByteBuffer.wrap("8]},\"tail\":{}}".getBytes(StandardCharsets.UTF_8)));
            assertEquals(8, session.read().value());
            assertEquals(JsonReadSession.State.NEED_INPUT, session.read().state());
            session.endInput();
            assertEquals(JsonReadSession.State.END, session.read().state());
        }
        assertEquals(List.of(4, 5), read(mapper(), "{\"data\":{\"rows\":[4,5]}}".getBytes(StandardCharsets.UTF_8),
                1, JsonType.of(Integer.class), options));
        for (String input : List.of("{}", "{\"data\":{\"rows\":1}}",
                "{\"data\":{\"rows\":[],\"rows\":[]}}")) {
            assertThrows(JsonException.class, () -> read(mapper(), input.getBytes(StandardCharsets.UTF_8),
                    1, JsonType.of(Object.class), options));
        }
        JsonReadOptions scalar = JsonReadOptions.selectValue(JsonPointer.of("/x/0")).withMaxValueBytes(1000);
        assertEquals(List.of(9), read(mapper(), "{\"x\":[9]}".getBytes(StandardCharsets.UTF_8), 1,
                JsonType.of(Integer.class), scalar));
    }

    @Test
    void emptyAndRootSelectionsRetainTheirBoundariesAtEverySplit() {
        JsonMapper json = mapper();
        for (String pointer : List.of("", "/rows")) {
            for (boolean elements : List.of(false, true)) {
                JsonReadOptions options = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                        new JsonReadOptions.Selection(JsonPointer.of(pointer), elements),
                        JsonReadOptions.NullPolicy.FAIL, 1000);
                for (String array : List.of("[]", "[1,[2]]")) {
                    String document = pointer.isEmpty() ? array : "{\"rows\":" + array + ",\"tail\":0}";
                    List<Object> contents = array.equals("[]") ? List.of() : List.of(1, List.of(2));
                    List<?> expected = elements ? contents : List.of(contents);
                    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
                    for (int split = 0; split <= bytes.length; split++) {
                        assertEquals(expected, read(json, bytes, split, JsonType.of(Object.class), options),
                                pointer + ":" + elements + ":" + split);
                    }
                }
            }
        }
    }

    @Test
    void selectionDistinguishesArrayIndicesFromNumericObjectFields() {
        JsonMapper json = mapper();
        for (String segment : List.of("", "0", "1", "01", "-1", "+1", "2147483647", "2147483648",
                "9223372036854775807", "9223372036854775808", "١")) {
            JsonReadOptions options = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                    new JsonReadOptions.Selection(new JsonPointer(List.of("rows", segment)), false),
                    JsonReadOptions.NullPolicy.FAIL, 1000);
            byte[] array = "{\"rows\":[7,8]}".getBytes(StandardCharsets.UTF_8);
            if (segment.equals("0") || segment.equals("1")) {
                assertEquals(List.of(segment.equals("0") ? 7 : 8),
                        read(json, array, 1, JsonType.of(Object.class), options));
            } else {
                assertThrows(JsonException.class, () -> read(json, array, 1, JsonType.of(Object.class), options),
                        segment);
            }
            byte[] object = json.writeBytes(Map.of("rows", Map.of(segment, 9)));
            assertEquals(List.of(9), read(json, object, 1, JsonType.of(Object.class), options), segment);
        }
    }

    @Test
    void byteBufferKindsAndLifecycleAreSupported() {
        for (int kind = 0; kind < 4; kind++) {
            ByteBuffer bytes = kind == 0 ? ByteBuffer.allocateDirect(20) : ByteBuffer.allocate(20);
            bytes.put((byte) 'x').put("[1,2]".getBytes(StandardCharsets.UTF_8)).put((byte) 'y');
            bytes.position(1).limit(6);
            if (kind == 2) {
                bytes = bytes.asReadOnlyBuffer();
            } else if (kind == 3) {
                bytes = bytes.slice();
            }
            bytes.mark();
            int position = bytes.position();
            int limit = bytes.limit();
            JsonReadSession<Integer> session = mapper().readSession(JsonType.of(Integer.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
            try (session) {
                session.feed(bytes);
                assertThrows(IllegalStateException.class, () -> session.feed(ByteBuffer.allocate(0)));
                assertEquals(1, session.read().value());
                assertEquals(2, session.read().value());
                assertEquals(JsonReadSession.State.NEED_INPUT, session.read().state());
                session.feed(ByteBuffer.allocate(0));
                session.endInput();
                assertThrows(IllegalStateException.class, session::endInput);
                assertEquals(JsonReadSession.State.END, session.read().state());
                assertEquals(position, bytes.position());
                assertEquals(limit, bytes.limit());
                bytes.reset();
            }
            session.close();
            assertFalse(session.needsInput());
            assertThrows(IllegalStateException.class, session::read);
        }
    }

    @Test
    void parserAndRecordBudgetsAreEnforced() {
        JsonOptions limits = new JsonOptions(true, false, true, false, false, 2, 10, 3, 30);
        JsonMapper limited = mapper(limits);
        assertThrows(JsonException.class, () -> limited.read("[[[1]]]", Object.class));
        assertThrows(JsonException.class, () -> limited.read("12345", Object.class));
        assertThrows(JsonException.class, () -> limited.read("\"01234567890123456789\"", Object.class));
        JsonReadOptions tiny = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE, null,
                JsonReadOptions.NullPolicy.FAIL, 5);
        JsonException error = assertThrows(JsonException.class, () -> read(mapper(),
                "{\"x\":\"long\"}".getBytes(StandardCharsets.UTF_8), 1, JsonType.of(Object.class), tiny));
        assertEquals(JsonException.Kind.LIMIT, error.kind());
        assertThrows(JsonException.class, () -> read(limited, "{\"x\":1,\"x\":2}", JsonReadOptions.Mode.SINGLE_VALUE));
    }

    @Test
    void recordBudgetCountsUtf8BytesWithoutRejectingValuesAtTheExactLimit() {
        for (String input : List.of("1", "true", "\"中\"", "[1]", "{\"x\":1}")) {
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            JsonReadOptions options = new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE, null,
                    JsonReadOptions.NullPolicy.FAIL, bytes.length);
            for (int split = 0; split <= bytes.length; split++) {
                assertEquals(1, read(mapper(), bytes, split, JsonType.of(Object.class), options).size());
            }
        }
    }

    private static List<Object> read(JsonMapper mapper, String input, JsonReadOptions.Mode mode) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        return read(mapper, bytes, Math.min(1, bytes.length), JsonType.of(Object.class), JsonReadOptions.of(mode));
    }

    private static <T> List<T> read(JsonMapper mapper, byte[] bytes, int split,
                                   JsonType<T> type, JsonReadOptions options) {
        List<T> result = new ArrayList<>();
        try (JsonReadSession<T> session = mapper.readSession(type, options)) {
            session.feed(ByteBuffer.wrap(bytes, 0, split));
            drain(session, result);
            session.feed(ByteBuffer.wrap(bytes, split, bytes.length - split));
            drain(session, result);
            session.endInput();
            assertEquals(JsonReadSession.State.END, drain(session, result));
        }
        return result;
    }

    private static <T> JsonReadSession.State drain(JsonReadSession<T> session, List<T> values) {
        while (true) {
            JsonReadSession.Result<T> result = session.read();
            if (result.state() != JsonReadSession.State.VALUE) {
                return result.state();
            }
            values.add(result.value());
        }
    }

    /** Public DTO exercises real property binding rather than a map-only engine path. */
    public static final class Item {
        public int value;

        public Item() {
        }

        public Item(int value) {
            this.value = value;
        }
    }

    /** Distinguishes a missing property from an explicit JSON null. */
    public static final class DefaultItem {
        public String value = "initial";
    }

    /** Fails within native property binding, retaining both array and property path segments. */
    public static final class FailingItem {
        public String getValue() {
            throw new IllegalStateException("broken property");
        }
    }

    /** Observes input token semantics before a custom reader builds its result. */
    public record TokenLog(String value) {
    }

    private static final class TokenLogModule implements JsonModule {
        @Override
        public String id() {
            return "token-log";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(TokenLog.class, new TokenLogAdapter());
        }
    }

    private static final class TokenLogAdapter implements JsonAdapter<TokenLog> {
        @Override
        public void write(TokenLog value, JsonWriter writer) {
            writer.value(value.value());
        }

        @Override
        public TokenLog read(JsonReader reader) {
            StringBuilder tokens = new StringBuilder();
            while (reader.next() != JsonToken.END_OBJECT) {
                tokens.append(reader.current()).append(':').append(reader.text()).append(';');
            }
            return new TokenLog(tokens.toString());
        }
    }

    /** Custom adapter fixture. */
    public record Label(String value) {
    }

    /** Non-final DTO verifies that neutral registrations never implicitly cover subclasses. */
    public static class BaseItem {
        public int value;

        public BaseItem(int value) {
            this.value = value;
        }
    }

    /** Subclass retains its independent bean binding when only its base class has an adapter. */
    public static final class DetailedItem extends BaseItem {
        public int extra;

        public DetailedItem() {
            super(0);
        }
    }

    private static final class ExactModule implements JsonModule {
        @Override
        public String id() {
            return "exact";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(BaseItem.class, new BaseAdapter());
            context.add(CharSequence.class, new SequenceAdapter());
        }
    }

    private static final class BaseAdapter implements JsonAdapter<BaseItem> {
        @Override
        public void write(BaseItem value, JsonWriter writer) {
            writer.value(value.value);
        }

        @Override
        public BaseItem read(JsonReader reader) {
            return new BaseItem(reader.readValue(JsonType.of(Integer.class)));
        }
    }

    private static final class SequenceAdapter implements JsonAdapter<CharSequence> {
        @Override
        public void write(CharSequence value, JsonWriter writer) {
            writer.value(value.toString());
        }

        @Override
        public CharSequence read(JsonReader reader) {
            return new StringBuilder(reader.readValue(JsonType.of(String.class)));
        }
    }

    private static final class LabelsModule implements JsonModule {
        @Override
        public String id() {
            return "labels";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(Label.class, new LabelAdapter());
        }
    }

    private static final class LabelAdapter implements JsonAdapter<Label> {
        @Override
        public void write(Label value, JsonWriter writer) {
            writer.value("label:" + value.value());
        }

        @Override
        public Label read(JsonReader reader) {
            return new Label(reader.readValue(JsonType.of(String.class)).substring(6));
        }
    }

    private static final class DuplicateLabelsModule implements JsonModule {
        @Override
        public String id() {
            return "duplicate";
        }

        @Override
        public void configure(JsonModuleContext context) {
            JsonModule labels = new LabelsModule();
            labels.configure(context);
            labels.configure(context);
        }
    }

    private static final class BrokenOutput extends java.io.OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("broken target");
        }
    }

    private static final class BrokenInput extends java.io.InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("broken source");
        }
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        private boolean closed;

        private TrackedInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackedOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
