package org.jetlinks.json.tck;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonFailure;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-engine tree access, configuration and diagnostic contract; inherited by each Provider TCK.
 * @since 1.0.0
 */
public abstract class JsonTreeContract {
    private static final JsonType<List<TreeHolder>> HOLDERS = new JsonType<>() { };
    private static final JsonType<List<JsonObject>> OBJECTS = new JsonType<>() { };
    protected abstract JsonMapper mapper(JsonOptions options, JsonModule... modules);

    @Test
    void structuralCopiesBoundCyclesAndDeepAcyclicInputs() {
        JsonMapper json = mapper(JsonOptions.DEFAULT.withMaxDepth(8));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("self", map);
        List<Object> list = new ArrayList<>();
        list.add(list);
        Object[] array = new Object[1];
        array[0] = array;
        List<Object> arrayHolder = new ArrayList<>();
        arrayHolder.add(array);
        Map<String, Object> mixed = new LinkedHashMap<>();
        List<Object> mixedList = new ArrayList<>();
        mixed.put("children", mixedList);
        mixedList.add(mixed);
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, () -> json.createObject(map)).kind());
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, () -> json.createArray(list)).kind());
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> json.createArray(arrayHolder)).kind());
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> json.createObject(mixed)).kind());
        JsonObject borrowed = json.wrap(map);
        assertEquals(1, borrowed.getObject("self").size());
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, borrowed::deepCopy).kind());
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, borrowed::toJavaValue).kind());
        Map<String, ?> deep = Map.of();
        for (int i = 0; i < 10000; i++) {
            deep = Map.of("child", deep);
        }
        Map<String, ?> source = deep;
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> json.createObject(source)).kind());
    }

    @Test
    void structuralDepthCountsContainersAndKeepsFailedMutationsAtomic() {
        JsonMapper shallow = mapper(JsonOptions.DEFAULT.withMaxDepth(1));
        JsonObject object = shallow.createObject(Map.of("keep", 1));
        JsonArray array = shallow.createArray(List.of(1));
        object.put("nested", Map.of());
        array.add(List.of());
        array.set(0, Map.of());
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, object::deepCopy).kind());
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, array::toJavaValue).kind());
        JsonObject borrowed = shallow.wrap(Map.of("nested", Map.of()));
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, borrowed::deepCopy).kind());
        JsonMapper twoLevels = mapper(JsonOptions.DEFAULT.withMaxDepth(2));
        Map<String, Object> child = new LinkedHashMap<>(Map.of("n", 1));
        JsonObject tree = twoLevels.createObject(Map.of("left", child, "right", child));
        tree.getObject("left").put("n", 2);
        assertEquals(1, tree.getObject("right").node("n").asInt());
        assertEquals(1, child.get("n"));
        assertEquals(2, tree.deepCopy().getObject("left").node("n").asInt());
    }

    @Test
    void borrowedViewsObserveSourceChangesAndRejectNestedWrites() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("n", "1"));
        List<Object> rows = new ArrayList<>(List.of(row));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a/b~c", rows);
        source.put("null", null);
        JsonObject view = json.wrap(source);
        JsonArray array = json.wrap(rows);
        for (JsonValue object : List.of(view, view.at(JsonPointer.of("")), array.getObject(0),
                view.getArray("a/b~c").node(0).asObject(),
                view.at(JsonPointer.of("/a~1b~0c/0")).asObject())) {
            assertThrows(UnsupportedOperationException.class, () -> object.asObject().put("x", 1));
            assertThrows(UnsupportedOperationException.class, () -> object.asObject().remove("n"));
        }
        for (JsonValue list : List.of(array, view.node("a/b~c").asArray(),
                view.at(JsonPointer.of("/a~1b~0c")), json.wrap(List.of(rows)).getArray(0),
                json.wrap(List.of(List.of(rows))).at(JsonPointer.of("/0/0")))) {
            assertThrows(UnsupportedOperationException.class, () -> list.asArray().add(1));
            assertThrows(UnsupportedOperationException.class, () -> list.asArray().set(0, 1));
        }
        assertEquals(Map.of("n", "1"), row);
        assertEquals(1, rows.size());
        assertFalse(source.containsKey("x"));
        row.put("n", "2");
        rows.add(Map.of("n", "3"));
        source.put("later", true);
        assertEquals(2, array.size());
        assertEquals(2, view.at(JsonPointer.of("/a~1b~0c/0/n")).asInt());
        assertEquals(3, array.getObject(1).node("n").asInt());
        assertTrue(view.node("later").asBoolean());
        JsonValue located = view.at(JsonPointer.of("/a~1b~0c/0/n"));
        assertEquals("/a~1b~0c/0/n", located.pointer().toString());
        assertEquals("[\"a/b~c\"][0].n", located.displayPath());
        assertTrue(view.node("missing").node(0).isMissing());
        assertNull(view.getObject("null"));
        assertNull(view.getObject("missing"));
        assertNull(view.getArray("null"));
        source.put("a/b~c", List.of());
        assertEquals(0, view.getArray("a/b~c").size());
        assertEquals(2, array.size());
    }

    @Test
    void borrowedCopiesExportsAndProviderConversionsDoNotExposeSourceStorage() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        Map<String, Object> row = new LinkedHashMap<>(Map.of("n", 1));
        List<Object> rows = new ArrayList<>(List.of(row));
        JsonObject view = json.wrap(Map.of("rows", rows));
        JsonObject copy = view.deepCopy();
        JsonArray arrayCopy = view.getArray("rows").deepCopy();
        Map<?, ?> exported = (Map<?, ?>) view.toJavaValue();
        Map<?, ?> converted = view.as(Map.class);
        JsonObject rebound = view.as(JsonObject.class);
        assertEquals(json.write(Map.of("rows", rows)), json.write(view));
        assertEquals(json.write(rows), json.write(json.wrap(rows)));
        copy.getArray("rows").getObject(0).put("n", 9);
        arrayCopy.set(0, Map.of("n", 8));
        arrayCopy.add(2);
        rebound.getArray("rows").getObject(0).put("n", 7);
        assertEquals(1, row.get("n"));
        row.put("n", 3);
        assertEquals(3, view.at(JsonPointer.of("/rows/0/n")).asInt());
        assertEquals(9, copy.at(JsonPointer.of("/rows/0/n")).asInt());
        assertEquals(8, arrayCopy.getObject(0).node("n").asInt());
        assertEquals(Map.of("rows", List.of(Map.of("n", 1))), exported);
        assertEquals(exported, converted);
        assertEquals("/n", view.getArray("rows").getObject(0).deepCopy().node("n").pointer().toString());
    }

    @Test
    void wrappingDoesNotVisitSourceContentsAndRejectsUnsupportedExposedLeaves() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        assertNotNull(json.wrap(new UnvisitedMap()));
        assertNotNull(json.wrap(new UnvisitedList()));
        assertThrows(NullPointerException.class, () -> json.wrap((Map<String, ?>) null));
        assertThrows(NullPointerException.class, () -> json.wrap((List<?>) null));
        for (Object invalid : List.of(new AtomicInteger(1), new Object(), new int[]{1},
                java.util.Set.of(1))) {
            JsonObject object = json.wrap(Map.of("bad", invalid));
            assertThrows(IllegalArgumentException.class, () -> object.get("bad"));
            assertThrows(IllegalArgumentException.class, () -> object.at(JsonPointer.of("/bad")));
            assertThrows(IllegalArgumentException.class, () -> json.wrap(List.of(invalid)).get(0));
        }
        JsonArray scalars = json.wrap(Arrays.asList(null, true, "s", (byte) 1, (short) 2,
                3, 4L, 5F, 6D, BigInteger.TEN, BigDecimal.TEN));
        for (int i = 0; i < scalars.size(); i++) {
            assertEquals(((List<?>) scalars.toJavaValue()).get(i), scalars.node(i).toJavaValue());
        }
    }

    @Test
    void structuralFactoriesRejectMutableNumbersWhileBindingNormalizesThem() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        AtomicInteger source = new AtomicInteger(7);
        assertThrows(IllegalArgumentException.class, () -> json.createObject(Map.of("n", source)));
        assertThrows(IllegalArgumentException.class, () -> json.createArray(List.of(source)));
        JsonValue bound = json.toTree(Map.of("n", source));
        JsonValue copy = bound.deepCopy();
        Map<?, ?> exported = (Map<?, ?>) bound.toJavaValue();
        assertEquals(Integer.class, exported.get("n").getClass());
        source.set(9);
        assertEquals(7, bound.node("n").asInt());
        assertEquals(7, copy.node("n").asInt());
        assertEquals(7, exported.get("n"));
        JsonArray array = json.createArray(List.of(BigInteger.TEN, new BigDecimal("1.2300")));
        JsonObject tree = json.createObject(Map.of("numbers", array));
        array.set(0, 11);
        assertEquals(BigInteger.TEN, tree.getArray("numbers").node(0).asBigInteger());
        assertEquals(new BigDecimal("1.2300"), tree.getArray("numbers").node(1).asBigDecimal());
    }

    @Test
    void scalarAccessUsesProviderAndDefaultsOnlyForAbsentInput() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        JsonObject tree = json.createObject(Map.of("n", "12", "flag", "true", "bad", "invalid"));
        JsonValue value = tree.node("n");
        assertEquals(12, value.asInt());
        assertEquals(12, value.asInteger());
        assertEquals(12L, value.asLong());
        assertEquals(12D, value.asDouble());
        assertEquals(12F, value.asFloat());
        assertEquals((short) 12, value.asShort());
        assertEquals((byte) 12, value.asByte());
        assertEquals("12", value.asString());
        assertEquals(new BigDecimal("12"), value.asBigDecimal());
        assertEquals(new BigInteger("12"), value.asBigInteger());
        assertTrue(tree.node("flag").asBoolean());
        tree.put("null", null);
        for (JsonValue absent : List.of(tree.node("missing"), tree.node("null"))) {
            assertEquals(0, absent.asInt());
            assertNull(absent.asInteger());
            assertEquals(0L, absent.asLong());
            assertEquals(0D, absent.asDouble());
            assertEquals(0F, absent.asFloat());
            assertEquals((short) 0, absent.asShort());
            assertEquals((byte) 0, absent.asByte());
            assertFalse(absent.asBoolean());
            assertNull(absent.asString());
            assertNull(absent.asBigDecimal());
            assertNull(absent.asBigInteger());
            assertEquals(7, absent.asInt(7));
            assertEquals(7, absent.asInteger(7));
            assertEquals(7L, absent.asLong(7L));
            assertEquals(7D, absent.asDouble(7D));
            assertEquals(7F, absent.asFloat(7F));
            assertEquals((short) 7, absent.asShort((short) 7));
            assertEquals((byte) 7, absent.asByte((byte) 7));
            assertTrue(absent.asBoolean(true));
            assertEquals("fallback", absent.asString("fallback"));
            assertEquals(BigDecimal.TEN, absent.asBigDecimal(BigDecimal.TEN));
            assertEquals(BigInteger.TEN, absent.asBigInteger(BigInteger.TEN));
            assertNull(absent.as(Integer.class));
        }
        JsonException error = assertThrows(JsonException.class, () -> tree.node("bad").asInt(7));
        assertEquals(JsonException.Kind.BINDING, error.kind());
        assertEquals(JsonFailure.Reason.CONVERSION, error.reason());
        assertEquals("/bad", error.pointer().toString());
        assertNotNull(error.getCause());
        tree.put("overflow", "2147483648");
        assertThrows(JsonException.class, () -> tree.node("overflow").asInteger(7));
        tree.put("wrong", Map.of());
        assertThrows(JsonException.class, () -> tree.node("wrong").asInt(7));
    }

    @Test
    void absentNodesStayChainableAndTypedContainersUseJavaNull() {
        JsonObject root = mapper(JsonOptions.DEFAULT).createObject();
        root.put("null", null);
        root.put("array", List.of(1));
        root.put("object", Map.of("n", 2));
        root.put("scalar", 3);
        assertNull(root.node("absent").node("items").node(0).asInteger());
        assertEquals(9, root.node("null").node("x").node("items").node(-1).asInt(9));
        assertEquals(1, root.getArray("array").node(0).asInt());
        assertEquals(2, root.getObject("object").node("n").asInt());
        for (String key : List.of("null", "unknown")) {
            assertNull(root.getObject(key));
            assertNull(root.getArray(key));
        }
        JsonArray nested = root.getArray("array");
        assertNull(nested.getObject(8));
        assertNull(nested.getArray(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> nested.get(8));
        JsonException shape = assertThrows(JsonException.class, () -> root.getArray("scalar"));
        assertEquals(JsonFailure.Reason.TYPE_MISMATCH, shape.reason());
        assertEquals("/scalar", shape.pointer().toString());
        assertThrows(JsonException.class, () -> nested.getObject(0));
        assertThrows(JsonException.class, () -> root.getObject("array"));
        assertThrows(JsonException.class, () -> root.node("scalar").getArray("child"));
        assertEquals(JsonException.Kind.VALIDATION,
                assertThrows(JsonException.class, () -> root.node("missing").requirePresent()).kind());
        assertEquals(1, nested.size());
    }

    @Test
    void assertionsCarryAttemptedPathsAndPreserveCustomExceptionIdentity() {
        JsonObject root = mapper(JsonOptions.DEFAULT).createObject();
        JsonValue missing = root.node("a").node("b.c").node("items").node(2).node("");
        JsonException error = assertThrows(JsonException.class, missing::requireNonNull);
        assertEquals(JsonException.Kind.VALIDATION, error.kind());
        assertEquals(JsonFailure.Reason.MISSING, error.reason());
        assertEquals("/a/b.c/items/2/", error.pointer().toString());
        assertEquals("a[\"b.c\"].items[2][\"\"]", error.displayPath());
        assertEquals(error.reason(), error.failure().reason());
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonFailure> context = new AtomicReference<>();
        IllegalArgumentException business = new IllegalArgumentException("business");
        assertSame(business, assertThrows(IllegalArgumentException.class, () -> missing.requireNonNull(failure -> {
            calls.incrementAndGet();
            context.set(failure);
            return business;
        })));
        assertEquals(1, calls.get());
        assertEquals(error.pointer(), context.get().pointer());
        root.put("n", 1);
        JsonValue present = root.node("n");
        assertSame(present, present.requirePresent(failure -> business));
        assertSame(present, present.requireNonNull(failure -> business));
        assertSame(present, present.require(value -> value.asInt() > 0, failure -> business));
        assertSame(present, present.require(value -> true));
        assertSame(present, present.requirePresent());
        assertSame(present, present.requireNonNull());
        assertSame(business, assertThrows(IllegalArgumentException.class, () -> present.require(value -> {
            throw business;
        })));
        assertSame(business, assertThrows(IllegalArgumentException.class,
                () -> present.require(value -> false, failure -> business)));
        assertSame(business, assertThrows(IllegalArgumentException.class,
                () -> missing.requirePresent(failure -> business)));
        assertEquals(JsonFailure.Reason.ASSERTION,
                assertThrows(JsonException.class, () -> present.require(value -> false)).reason());
        root.put("null", null);
        JsonValue explicitNull = root.node("null");
        assertSame(explicitNull, explicitNull.requirePresent());
        assertEquals(JsonFailure.Reason.NULL,
                assertThrows(JsonException.class, explicitNull::requireNonNull).reason());
        assertThrows(NullPointerException.class, () -> missing.requireNonNull(failure -> null));
        assertThrows(NullPointerException.class, () -> missing.requireNonNull(null));
    }

    @Test
    void configuredMapperFollowsEveryTreeBindingEntryPointAndCopiedDestination() {
        JsonMapper first = mapper(JsonOptions.DEFAULT, new OffsetModule(10));
        JsonMapper second = mapper(JsonOptions.DEFAULT, new OffsetModule(20));
        String input = "{\"n\":\"1\",\"child\":{\"n\":\"2\"}}";
        for (JsonValue tree : List.of(first.createObject(Map.of("n", "1")),
                first.readTree(input), first.toTree(Map.of("n", "1")),
                first.read(input, JsonValue.class), first.read(input, JsonObject.class),
                first.convert(Map.of("n", "1"), JsonType.of(JsonObject.class)),
                first.createArray(List.of(Map.of("n", "1"))).node(0),
                first.wrap(Map.of("n", "1")), first.wrap(List.of(Map.of("n", "1"))).node(0))) {
            assertEquals(11, tree.node("n").asInteger());
            assertEquals(11, tree.deepCopy().node("n").asInteger());
        }
        JsonObject source = first.readTree(input).asObject();
        assertEquals(12, source.getObject("child").node("n").asInteger());
        assertEquals(12, source.at(JsonPointer.of("/child/n")).asInteger());
        assertEquals(12, source.at(JsonPointer.of("/child/n")).deepCopy().asInteger());
        JsonObject copied = source.getObject("child").deepCopy();
        assertEquals("/n", copied.node("n").pointer().toString());
        assertEquals(12, copied.node("n").asInteger());
        JsonObject destination = second.createObject();
        destination.put("inserted", source);
        assertEquals(21, destination.getObject("inserted").node("n").asInteger());
        assertEquals(22, destination.at(JsonPointer.of("/inserted/child/n")).asInteger());
        assertEquals("/inserted/n", destination.getObject("inserted").node("n").pointer().toString());
        TreeHolder holder = first.read("{\"object\":{\"n\":\"1\"},\"array\":[\"2\"]}", TreeHolder.class);
        assertEquals(11, holder.object().node("n").asInteger());
        assertEquals(12, holder.array().node(0).asInteger());
        List<TreeHolder> holders = first.read("[{\"object\":{\"n\":\"1\"}}]", HOLDERS);
        assertEquals(11, holders.get(0).object().node("n").asInteger());
        List<JsonObject> rows = first.createArray(List.of(Map.of("n", "1"))).as(OBJECTS);
        assertEquals(11, rows.get(0).node("n").asInteger());
        assertEquals(12, first.read("[\"2\"]", JsonArray.class).node(0).asInteger());
        assertNull(first.read("null", JsonObject.class));
        assertNull(first.read("null", JsonArray.class));
        try (var reader = first.reader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(JsonToken.START_OBJECT, reader.next());
            assertEquals(11, reader.readValue(JsonType.of(JsonObject.class)).node("n").asInteger());
        }
        byte[] bytes = ("[" + input + "]").getBytes(StandardCharsets.UTF_8);
        List<JsonObject> records = new ArrayList<>();
        try (JsonReadSession<JsonObject> session = first.readSession(JsonType.of(JsonObject.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
            for (byte b : bytes) {
                if (session.needsInput()) {
                    session.feed(ByteBuffer.wrap(new byte[]{b}));
                }
                drainTrees(session, records);
            }
            session.endInput();
            drainTrees(session, records);
        }
        assertEquals(1, records.size());
        assertEquals(11, records.get(0).node("n").asInteger());
    }

    @Test
    void bindingErrorsComposeNativeFieldAndArrayPathsWithTheAccessPrefix() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        JsonValue node = json.readTree("{\"payload\":{\"rows\":[{\"value\":\"invalid\"}]}}").node("payload");
        JsonException error = assertThrows(JsonException.class, () -> node.as(Batch.class));
        assertEquals(JsonException.Kind.BINDING, error.kind());
        assertEquals("/payload/rows/0/value", error.pointer().toString());
        assertEquals("payload.rows[0].value", error.displayPath());
        assertNotNull(error.getCause());
        assertFalse(error.getCause() instanceof JsonException);
        JsonException read = assertThrows(JsonException.class,
                () -> json.read("{\"rows\":[{\"value\":\"invalid\"}]}", Batch.class));
        assertEquals("/rows/0/value", read.pointer().toString());
        assertEquals("rows[0].value", read.displayPath());
        JsonObject special = json.createObject();
        special.put("a/b~c", Map.of("q\"\\\n", "invalid"));
        JsonException escaped = assertThrows(JsonException.class, () -> special.node("a/b~c").node("q\"\\\n").asInt());
        assertEquals("/a~1b~0c/q\"\\\n", escaped.pointer().toString());
        assertEquals("[\"a/b~c\"][\"q\\\"\\\\\\u000a\"]", escaped.displayPath());
        assertEquals(12, json.readTree("{\"a\":[12]}").at(JsonPointer.of("/a/0")).asInt());
        for (String invalid : Arrays.asList("01", "-1", "+1", "2147483647", "2147483648",
                "9223372036854775807", "9223372036854775808", "99999999999999999999", "", "١")) {
            JsonValue absent = json.readTree("{\"a\":[12]}").at(new JsonPointer(List.of("a", invalid, "last")));
            assertTrue(absent.isMissing());
            assertEquals(List.of("a", invalid, "last"), absent.pointer().segments());
        }
        JsonValue numericKeys = json.createObject(Map.of("01", 1, "2147483648", 2, "9223372036854775808", 3));
        assertEquals(1, numericKeys.at(JsonPointer.of("/01")).asInt());
        assertEquals(2, numericKeys.at(JsonPointer.of("/2147483648")).asInt());
        assertEquals(3, numericKeys.at(JsonPointer.of("/9223372036854775808")).asInt());
    }

    @Test
    void invalidApiArgumentsStayOutsideProviderErrorNormalization() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        assertThrows(NullPointerException.class,
                () -> json.read((java.io.InputStream) null, JsonType.of(Object.class)));
        assertThrows(NullPointerException.class,
                () -> json.read((java.io.Reader) null, JsonType.of(Object.class)));
        assertThrows(NullPointerException.class, () -> json.convert(1, (JsonType<Object>) null));
        assertThrows(NullPointerException.class, () -> json.convert(1, (Class<Object>) null));
        assertThrows(NullPointerException.class, () -> json.reader(null));
        assertThrows(NullPointerException.class, () -> json.writer(null));
        assertThrows(NullPointerException.class, () -> json.compactWriter(null));
        assertThrows(NullPointerException.class, () -> json.write(1, (java.io.OutputStream) null));
        assertThrows(NullPointerException.class, () -> json.write(1, (java.io.Writer) null));
        assertThrows(NullPointerException.class, () -> json.writeCompact(1, null));
        assertThrows(NullPointerException.class, () -> json.readSession(JsonType.of(Object.class), null));
    }

    @Test
    void nestedNativeStringLimitsKeepLimitClassificationThroughPropertyBinding() {
        JsonMapper limited = mapper(new JsonOptions(true, false, false, false, false, 16, 3, 128, 8192));
        JsonException error = assertThrows(JsonException.class,
                () -> limited.read("{\"value\":\"0123456789\"}", JsonMapperContract.DefaultItem.class));
        assertEquals(JsonException.Kind.LIMIT, error.kind());
        assertNotNull(error.getCause());
    }

    private static void drainTrees(JsonReadSession<JsonObject> session, List<JsonObject> records) {
        while (true) {
            var result = session.read();
            if (result.state() != JsonReadSession.State.VALUE) {
                return;
            }
            records.add(result.value());
        }
    }

    private static final class UnvisitedMap extends java.util.AbstractMap<String, Object> {
        @Override
        public java.util.Set<Entry<String, Object>> entrySet() {
            throw new AssertionError("Wrapping must not enumerate source entries");
        }
    }

    private static final class UnvisitedList extends java.util.AbstractList<Object> {
        @Override
        public Object get(int index) {
            throw new AssertionError("Wrapping must not read source elements");
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /** Typed tree fields must preserve their configured mapper through native DTO binding. */
    public record TreeHolder(JsonObject object, JsonArray array) {
    }

    /** Exercises native nested-field and index diagnostics during conversion. */
    public record Batch(List<JsonMapperContract.Item> rows) {
        public Batch {
            rows = rows == null ? null : List.copyOf(rows);
        }
    }

    private record OffsetModule(int offset) implements JsonModule {
        @Override
        public String id() {
            return "offset";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(Integer.class, new JsonAdapter<>() {
                @Override
                public void write(Integer value, org.jetlinks.json.JsonWriter writer) {
                    writer.value(value.toString());
                }

                @Override
                public Integer read(org.jetlinks.json.JsonReader reader) {
                    return Integer.parseInt(reader.readValue(JsonType.of(String.class))) + offset;
                }
            });
        }
    }
}
