package org.jetlinks.json.tck;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
        assertEquals(7, bound.get("n").asInt());
        assertEquals(7, copy.get("n").asInt());
        assertEquals(7, exported.get("n"));
        JsonArray array = json.createArray(List.of(BigInteger.TEN, new BigDecimal("1.2300")));
        JsonObject tree = json.createObject(Map.of("numbers", array));
        array.set(0, 11);
        assertEquals(BigInteger.TEN, tree.getArray("numbers").get(0).asBigInteger());
        assertEquals(new BigDecimal("1.2300"), tree.getArray("numbers").get(1).asBigDecimal());
    }

    @Test
    void scalarAccessUsesProviderAndDefaultsOnlyForAbsentInput() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        JsonObject tree = json.createObject(Map.of("n", "12", "flag", "true", "bad", "invalid"));
        JsonValue value = tree.get("n");
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
        assertTrue(tree.get("flag").asBoolean());
        for (JsonValue absent : List.of(tree.get("missing"), tree.put("null", null).get("null"))) {
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
        JsonException error = assertThrows(JsonException.class, () -> tree.get("bad").asInt(7));
        assertEquals(JsonException.Kind.BINDING, error.kind());
        assertEquals(JsonFailure.Reason.CONVERSION, error.reason());
        assertEquals("/bad", error.pointer().toString());
        assertNotNull(error.getCause());
        assertThrows(JsonException.class, () -> tree.put("overflow", "2147483648").get("overflow").asInteger(7));
        assertThrows(JsonException.class, () -> tree.put("wrong", Map.of()).get("wrong").asInt(7));
    }

    @Test
    void absentTypedViewsStayChainableDistinctAndReadOnly() {
        JsonObject root = mapper(JsonOptions.DEFAULT).createObject();
        root.put("null", null).put("array", List.of(1)).put("object", Map.of("n", 2)).put("scalar", 3);
        assertNull(root.get("absent").getArray("items").get(0).asInteger());
        assertEquals(9, root.get("null").getObject("x").getArray("items").get(-1).asInt(9));
        assertEquals(1, root.getArray("array").get(0).asInt());
        assertEquals(2, root.getObject("object").get("n").asInt());
        JsonArray nested = root.getArray("array");
        assertTrue(nested.getObject(8).isMissing());
        assertTrue(nested.getArray(-1).isMissing());
        JsonArray nullView = root.getArray("null");
        assertTrue(nullView.isNull());
        assertFalse(nullView.isMissing());
        assertNull(nullView.toJavaValue());
        assertEquals(0, nullView.size());
        assertTrue(nullView.deepCopy().isNull());
        JsonObject missingView = root.getObject("unknown");
        assertTrue(missingView.isMissing());
        assertTrue(missingView.deepCopy().isMissing());
        assertTrue(root.getArray("unknown").deepCopy().isMissing());
        assertThrows(IllegalStateException.class, missingView::toJavaValue);
        assertThrows(JsonException.class, () -> nullView.add(1));
        assertThrows(JsonException.class, () -> nullView.set(0, 1));
        assertThrows(JsonException.class, () -> missingView.put("x", 1));
        assertThrows(JsonException.class, () -> missingView.remove("x"));
        assertThrows(JsonException.class, nullView::asArray);
        assertThrows(JsonException.class, missingView::asObject);
        JsonException shape = assertThrows(JsonException.class, () -> root.getArray("scalar"));
        assertEquals(JsonFailure.Reason.TYPE_MISMATCH, shape.reason());
        assertEquals("/scalar", shape.pointer().toString());
        assertThrows(JsonException.class, () -> nested.getObject(0));
        assertThrows(JsonException.class, () -> root.getObject("array"));
        assertThrows(JsonException.class, () -> root.get("scalar").getArray("child"));
        assertThrows(JsonException.class, () -> root.get("scalar").getObject(0));
        assertThrows(JsonException.class, () -> root.getObject(0));
        assertThrows(JsonException.class, () -> root.getArray(0));
        assertTrue(root.getObject("null").isNull());
        assertTrue(root.getObject("null").deepCopy().isNull());
        assertTrue(root.getArray("absent").getObject("child").isMissing());
        assertTrue(nested.getArray(8).isMissing());
        assertEquals(JsonException.Kind.VALIDATION,
                assertThrows(JsonException.class, () -> root.get("missing").requirePresent()).kind());
        assertEquals(1, root.getArray("array").size());
        assertTrue(root.get("never-created").isMissing());
    }

    @Test
    void assertionsCarryAttemptedPathsAndPreserveCustomExceptionIdentity() {
        JsonObject root = mapper(JsonOptions.DEFAULT).createObject();
        JsonValue missing = root.get("a").getObject("b.c").getArray("items").get(2).get("");
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
        JsonValue present = root.put("n", 1).get("n");
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
        JsonValue explicitNull = root.put("null", null).get("null");
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
                first.createArray(List.of(Map.of("n", "1"))).get(0))) {
            assertEquals(11, tree.get("n").asInteger());
            assertEquals(11, tree.deepCopy().get("n").asInteger());
        }
        JsonObject source = first.readTree(input).asObject();
        assertEquals(12, source.getObject("child").get("n").asInteger());
        JsonObject copied = source.getObject("child").deepCopy();
        assertEquals("/n", copied.get("n").pointer().toString());
        assertEquals(12, copied.get("n").asInteger());
        JsonValue destination = second.createObject().put("inserted", source);
        assertEquals(21, destination.getObject("inserted").get("n").asInteger());
        assertEquals("/inserted/n", destination.getObject("inserted").get("n").pointer().toString());
        TreeHolder holder = first.read("{\"object\":{\"n\":\"1\"},\"array\":[\"2\"]}", TreeHolder.class);
        assertEquals(11, holder.object().get("n").asInteger());
        assertEquals(12, holder.array().get(0).asInteger());
        List<TreeHolder> holders = first.read("[{\"object\":{\"n\":\"1\"}}]", HOLDERS);
        assertEquals(11, holders.get(0).object().get("n").asInteger());
        List<JsonObject> rows = first.createArray(List.of(Map.of("n", "1"))).as(OBJECTS);
        assertEquals(11, rows.get(0).get("n").asInteger());
        assertEquals(12, first.read("[\"2\"]", JsonArray.class).get(0).asInteger());
        assertTrue(first.read("null", JsonObject.class).isNull());
        assertTrue(first.read("null", JsonArray.class).isNull());
        try (var reader = first.reader(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))) {
            assertEquals(JsonToken.START_OBJECT, reader.next());
            assertEquals(11, reader.readValue(JsonType.of(JsonObject.class)).get("n").asInteger());
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
        assertEquals(11, records.get(0).get("n").asInteger());
    }

    @Test
    void bindingErrorsComposeNativeFieldAndArrayPathsWithTheAccessPrefix() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        JsonValue node = json.readTree("{\"payload\":{\"rows\":[{\"value\":\"invalid\"}]}}").get("payload");
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
        JsonValue special = json.createObject().put("a/b~c", Map.of("q\"\\\n", "invalid"));
        JsonException escaped = assertThrows(JsonException.class, () -> special.get("a/b~c").get("q\"\\\n").asInt());
        assertEquals("/a~1b~0c/q\"\\\n", escaped.pointer().toString());
        assertEquals("[\"a/b~c\"][\"q\\\"\\\\\\u000a\"]", escaped.displayPath());
        assertEquals(12, json.readTree("{\"a\":[12]}").at(JsonPointer.of("/a/0")).asInt());
        for (String invalid : Arrays.asList("01", "-1", "+1", "99999999999999999999", "", "١")) {
            JsonValue absent = json.readTree("{\"a\":[12]}").at(new JsonPointer(List.of("a", invalid, "last")));
            assertTrue(absent.isMissing());
            assertEquals(List.of("a", invalid, "last"), absent.pointer().segments());
        }
    }

    @Test
    void invalidApiArgumentsStayOutsideProviderErrorNormalization() {
        JsonMapper json = mapper(JsonOptions.DEFAULT);
        assertThrows(NullPointerException.class,
                () -> json.read((java.io.InputStream) null, JsonType.of(Object.class)));
        assertThrows(NullPointerException.class,
                () -> json.read((java.io.Reader) null, JsonType.of(Object.class)));
        assertThrows(NullPointerException.class, () -> json.convert(1, null));
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
