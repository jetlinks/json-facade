package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTreeApiTest {
    @Test
    void staticStructuralCopiesUseTheDefaultDepthBudget() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, () -> JsonValue.of(cyclic)).kind());
        Map<String, ?> deep = Map.of();
        for (int i = 0; i < 10000; i++) {
            deep = Map.of("child", deep);
        }
        Map<String, ?> source = deep;
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, () -> JsonValue.of(source)).kind());
        JsonObject object = JsonValue.object().put("keep", 1);
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> object.put("cycle", cyclic)).kind());
        assertEquals(Map.of("keep", 1), object.toJavaValue());
    }

    @Test
    void pointerRangesComposeWithExistingLocationsAndKeepCopiesIndependent() {
        JsonObject root = JsonValue.object().put("items", List.of(Map.of("0", Map.of("a/b", 7))));
        JsonPointer pointer = JsonPointer.of("/items/0/0/a~1b");
        JsonValue selected = root.at(pointer);
        assertEquals(7, selected.toJavaValue());
        assertEquals(pointer, selected.pointer());
        assertEquals("items[0][\"0\"][\"a/b\"]", selected.displayPath());
        assertEquals(selected.displayPath(), root.get("items").at(JsonPointer.of("/0/0"))
                .get("a/b").displayPath());
        assertEquals(pointer, root.at(JsonPointer.of("/items/0"))
                .at(JsonPointer.of("/0/a~1b")).pointer());
        assertSame(root, root.at(JsonPointer.of("")));
        assertThrows(NullPointerException.class, () -> root.at(null));

        JsonValue absent = selected.at(JsonPointer.of("/missing/tail")).get("~");
        JsonException error = assertThrows(JsonException.class, absent::requirePresent);
        assertEquals("/items/0/0/a~1b/missing/tail/~0", error.pointer().toString());
        assertEquals("items[0][\"0\"][\"a/b\"].missing.tail[\"~\"]", error.displayPath());

        JsonValue parent = root.at(JsonPointer.of("/items/0"));
        List<Integer> input = new ArrayList<>(List.of(1));
        parent.put("copy", Map.of("values", input));
        input.add(2);
        assertEquals(List.of(1), root.at(JsonPointer.of("/items/0/copy/values")).toJavaValue());
        JsonObject copy = parent.asObject().deepCopy();
        copy.getObject("copy").getArray("values").add(3);
        assertEquals(List.of(1), parent.at(JsonPointer.of("/copy/values")).toJavaValue());
        assertEquals("", copy.pointer().toString());
        assertEquals("$", copy.displayPath());
    }

    @Test
    void pointerTraversalKeepsIndexDiagnosticsAndAbsentKinds() {
        JsonObject root = JsonValue.object().put("items", List.of(Map.of("0", 7))).put("nil", null);
        for (String index : List.of("0", "1", "2147483647", "01", "-1", "-", "2147483648",
                "9223372036854775808", "")) {
            JsonValue selected = root.at(new JsonPointer(List.of("items", index, "tail")));
            assertTrue(selected.isMissing());
            assertFalse(selected.isNull());
            assertEquals(List.of("items", index, "tail"), selected.pointer().segments());
            String display = List.of("0", "1", "2147483647").contains(index)
                    ? "items[" + index + "].tail" : "items[\"" + index + "\"].tail";
            assertEquals(display, selected.displayPath());
        }
        for (String name : List.of("nil", "missing")) {
            JsonValue selected = root.getArray(name).at(JsonPointer.of("/0/tail"));
            assertTrue(selected.isMissing());
            assertEquals(name + "[0].tail", selected.displayPath());
            assertEquals("/" + name + "/0/tail", selected.pointer().toString());
        }
        JsonValue nullable = JsonValue.object().put("a", JsonValue.object().put("nil", null));
        assertTrue(nullable.at(JsonPointer.of("/a/nil")).isNull());
        assertTrue(nullable.at(JsonPointer.of("/a/nil/tail")).isMissing());
        assertTrue(JsonValue.NULL.at(JsonPointer.of("/a/b")).isMissing());
        assertTrue(JsonValue.MISSING.at(JsonPointer.of("/a/b")).isMissing());
    }

    @Test
    void structuralNumbersMustHaveKnownImmutableRuntimeClasses() {
        List<Number> immutable = List.of((byte) 1, (short) 2, 3, 4L, 5F, 6D, BigInteger.TEN,
                new BigDecimal("1.2300"));
        JsonArray tree = JsonValue.of(immutable).asArray();
        assertEquals(immutable, tree.toJavaValue());
        assertEquals(immutable, tree.deepCopy().toJavaValue());
        LongAdder adder = new LongAdder();
        adder.add(7);
        for (Number mutable : List.of(new AtomicInteger(7), new AtomicLong(7), adder,
                new BigInteger("7") { private static final long serialVersionUID = 1L; },
                new BigDecimal("7") { private static final long serialVersionUID = 1L; })) {
            assertThrows(IllegalArgumentException.class, () -> JsonValue.of(mutable));
            assertThrows(IllegalArgumentException.class, () -> JsonValue.of(Map.of("n", List.of(mutable))));
            JsonObject object = JsonValue.object().put("n", 1);
            assertThrows(IllegalArgumentException.class, () -> object.put("n", mutable));
            assertEquals(1, object.get("n").toJavaValue());
            assertThrows(IllegalArgumentException.class, () -> tree.add(mutable));
            assertThrows(IllegalArgumentException.class, () -> tree.set(0, mutable));
            assertEquals(immutable, tree.toJavaValue());
            JsonValue external = new ForeignValue(mutable, false, JsonPointer.of(""), "$");
            assertThrows(IllegalArgumentException.class, () -> object.put("n", external));
        }
    }

    @Test
    void foreignAbsentViewsPreservePublicLocationsAndDetachCopies() {
        for (boolean missing : List.of(true, false)) {
            JsonValue foreign = new ForeignValue(null, missing,
                    JsonPointer.of("/external/0/a~1b"), "external[\"0\"][\"a/b\"]");
            JsonArray array = foreign.getArray("items");
            assertTrue(array.isMissing());
            assertEquals("/external/0/a~1b/items", array.pointer().toString());
            JsonValue child = array.get(0).getObject("~").get("");
            JsonException failure = assertThrows(JsonException.class, child::requireNonNull);
            assertEquals("/external/0/a~1b/items/0/~0/", failure.pointer().toString());
            assertEquals("external[\"0\"][\"a/b\"].items[0][\"~\"][\"\"]", failure.displayPath());
            assertEquals("", array.deepCopy().pointer().toString());
            assertEquals("$", array.deepCopy().displayPath());
            JsonArray view = org.jetlinks.json.internal.JsonValues.arrayView(foreign);
            assertEquals(missing, view.isMissing());
            assertEquals(!missing, view.isNull());
            assertEquals(foreign.pointer(), view.pointer());
            assertEquals(foreign.displayPath(), view.displayPath());
            JsonValue selected = view.at(JsonPointer.of("/0/tail"));
            assertEquals("/external/0/a~1b/0/tail", selected.pointer().toString());
            assertEquals("external[\"0\"][\"a/b\"][0].tail", selected.displayPath());
            assertEquals(foreign.pointer(), assertThrows(JsonException.class, () -> view.add(1)).pointer());
            assertEquals("", view.deepCopy().pointer().toString());
        }
        JsonValue root = new ForeignValue(null, true, JsonPointer.of(""), "$");
        assertEquals("items[0]", root.getArray("items").get(0).displayPath());
        JsonObject view = org.jetlinks.json.internal.JsonValues.objectView(root);
        assertFalse(view.isNull());
        assertEquals("$", view.displayPath());
    }

    @Test
    void foreignExportedContainersAreCopiedOnInsertion() {
        List<Object> source = new ArrayList<>(List.of(1));
        JsonValue foreign = new ForeignValue(Map.of("items", source), false, JsonPointer.of(""), "$");
        JsonObject tree = JsonValue.object().put("value", foreign);
        source.add(2);
        assertEquals(List.of(1), tree.get("value").get("items").toJavaValue());
    }

    @Test
    void staticTreesNavigateWithoutDiscoveringProvidersAndRequireExplicitBindingForPresentValues() {
        JsonValue root = JsonValue.object().put("n", 1).put("null", null);
        assertEquals(JsonException.Kind.CONFIGURATION,
                assertThrows(JsonException.class, () -> root.get("n").asInteger()).kind());
        assertNull(root.get("absent").getArray("items").get(0).asInteger());
        assertNull(root.get("null").asInteger());
        assertEquals(0, JsonValue.NULL.asInt());
        assertThrows(NullPointerException.class, () -> root.get("absent").as((JsonType<Object>) null));
        assertEquals("$", JsonValue.NULL.displayPath());
        assertEquals("", JsonValue.NULL.pointer().toString());
        assertEquals("/a~1b/~0/", new JsonPointer(List.of("a/b", "~", "")).toString());
        assertEquals("[\"$\"]", root.get("$").displayPath());
        assertEquals("[\"1\"]", root.get("1").displayPath());
        assertEquals("[\"a-b\"]", root.get("a-b").displayPath());
        assertEquals("node", root.get("node").displayPath());
        assertTrue(root.get("n").get("child").isMissing());
    }

    @Test
    void failureMetadataSerializesAndContextEnrichmentKeepsTheOriginalCauseAndStack() throws Exception {
        IOException cause = new IOException("io");
        JsonException original = new JsonException(JsonException.Kind.IO, "operation", cause);
        original.addSuppressed(new IllegalStateException("cleanup"));
        JsonException contextual = original.at(JsonPointer.of("/a"), "a");
        assertSame(cause, contextual.getCause());
        assertSame(original.getSuppressed()[0], contextual.getSuppressed()[0]);
        assertEquals(original.getStackTrace()[0], contextual.getStackTrace()[0]);
        assertNull(original.pointer());
        assertEquals("/a", contextual.pointer().toString());
        assertEquals(JsonFailure.Reason.UNSPECIFIED, contextual.reason());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(contextual);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            JsonException restored = (JsonException) input.readObject();
            assertEquals(contextual.pointer(), restored.pointer());
            assertEquals("a", restored.displayPath());
            assertEquals(JsonException.Kind.IO, restored.kind());
            assertEquals("io", restored.getCause().getMessage());
        }
        JsonException indexed = new JsonException(new JsonFailure(JsonException.Kind.BINDING,
                JsonFailure.Reason.CONVERSION, "bad", JsonPointer.of("/0"), "[0]", cause));
        assertEquals("a[0]", indexed.at(JsonPointer.of("/a"), "a").displayPath());
        assertEquals("[0]", indexed.at(JsonPointer.of(""), "$").displayPath());
        JsonException root = new JsonException(new JsonFailure(JsonException.Kind.BINDING,
                JsonFailure.Reason.UNSPECIFIED, "bad", JsonPointer.of(""), "$", null));
        assertEquals("a", root.at(JsonPointer.of("/a"), "a").displayPath());
        assertEquals(JsonFailure.Reason.CONVERSION, root.at(JsonPointer.of("/a"), "a").reason());
    }

    private record ForeignValue(Object value, boolean isMissing, JsonPointer pointer,
                                String displayPath) implements JsonValue {
        @Override
        public boolean isNull() {
            return !isMissing && value == null;
        }

        @Override
        public Object toJavaValue() {
            if (isMissing) {
                throw new IllegalStateException("missing");
            }
            return value;
        }

        @Override
        public JsonValue get(String name) {
            return child(name, displayPath.equals("$") ? name : displayPath + "." + name);
        }

        @Override
        public JsonValue get(int index) {
            return child(Integer.toString(index), displayPath + "[" + index + "]");
        }

        private JsonValue child(String name, String display) {
            List<String> segments = new ArrayList<>(pointer.segments());
            segments.add(name);
            return new ForeignValue(null, true, new JsonPointer(segments), display);
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public JsonValue deepCopy() {
            return new ForeignValue(value, isMissing, JsonPointer.of(""), "$");
        }
    }
}
