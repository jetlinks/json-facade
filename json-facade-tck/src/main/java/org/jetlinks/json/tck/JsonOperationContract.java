package org.jetlinks.json.tck;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jetlinks.json.JsonAttribute;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonReadSession;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.spi.JsonAdapter;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/** 两个真实 Provider 共用的操作隔离及配置传播契约。 @since 1.0.0 */
public abstract class JsonOperationContract extends JsonTreeContract {
    private static final JsonAttribute<Integer> OFFSET = JsonAttribute.of("offset", Integer.class);

    @Test
    void operationOverridesInheritAndSupportExplicitFalse() {
        JsonMapper base = mapper(JsonOptions.DEFAULT.withPretty(true).withOmitNulls(true)
                .withFailDuplicateKeys(true).withFailUnknownProperties(true));
        assertSame(base, base.withOptions(JsonOperationOptions.EMPTY));
        assertThrows(NullPointerException.class, () -> base.withOptions(null));
        var overrides = JsonOperationOptions.builder().pretty(false).omitNulls(false)
                .losslessNumbers(false).failDuplicateKeys(false).failUnknownProperties(false).build();
        JsonMapper operation = base.withOptions(overrides);
        assertFalse(operation.options().pretty());
        assertTrue(base.options().pretty());
        assertInstanceOf(Double.class, operation.read("1.25", Object.class));
        assertInstanceOf(BigDecimal.class, base.read("1.25", Object.class));
        assertEquals(new Name("x"), operation.read("{\"name\":\"x\",\"extra\":1}", Name.class));
        assertThrows(JsonException.class, () -> base.read("{\"name\":\"x\",\"extra\":1}", Name.class));
        assertEquals(Map.of("x", 2), operation.read("{\"x\":1,\"x\":2}", Map.class));
        try (var session = operation.readSession(Object.class,
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE))) {
            session.feed(ByteBuffer.wrap("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8)));
            session.endInput();
            assertEquals(Map.of("x", 2), session.read().value());
            assertEquals(JsonReadSession.State.END, session.read().state());
        }
        assertThrows(JsonException.class, () -> base.read("{\"x\":1,\"x\":2}", Map.class));
        JsonMapper restored = operation.withOptions(JsonOperationOptions.builder().pretty(true)
                .losslessNumbers(true).failUnknownProperties(true).failDuplicateKeys(true).build());
        assertTrue(restored.options().pretty());
        assertInstanceOf(BigDecimal.class, restored.read("1.25", Object.class));
        assertThrows(JsonException.class, () -> restored.read("{\"x\":1,\"x\":2}", Map.class));
    }

    @Test
    void nullOverridesIsolateWarmNativeCachesAndKeepArrayPositions() {
        for (boolean omit : List.of(false, true)) {
            JsonMapper base = mapper(JsonOptions.DEFAULT.withOmitNulls(omit));
            Nullable value = new Nullable(null, new Name("x"));
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("missing", null);
            map.put("items", java.util.Arrays.asList((Object) null, mapOfNull()));
            String original = base.write(value);
            base.write(map);
            JsonMapper changed = base.withOptions(JsonOperationOptions.builder().omitNulls(!omit).build());
            assertEquals(omit, !changed.readTree(changed.write(value)).get("missing").isMissing());
            assertEquals(original, base.write(value));
            assertEquals(omit, !changed.readTree(changed.write(map)).get("missing").isMissing());
            assertEquals(2, changed.readTree(changed.write(map)).get("items").size());
            assertTrue(changed.readTree(changed.write(map)).get("items").get(0).isNull());
            JsonMapper restored = changed.withOptions(JsonOperationOptions.builder().omitNulls(omit).build());
            assertEquals(original, restored.write(value));
            assertEquals(!omit, !restored.toTree(value).get("missing").isMissing());
        }
    }

    @Test
    void attributesReachNestedAdaptersAcrossSynchronousInputsAndOutputs() {
        JsonMapper base = mapper(JsonOptions.DEFAULT, new AttributeModule());
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().attribute(OFFSET, 10).build());
        Wrapped expected = new Wrapped(new Adjusted(2));
        assertEquals("12", operation.write(expected));
        assertEquals("2", base.write(expected));
        assertEquals(expected, operation.read("12", Wrapped.class));
        assertEquals(expected, operation.read("12".getBytes(StandardCharsets.UTF_8), Wrapped.class));
        assertEquals(expected, operation.read(new StringReader("12"), Wrapped.class));
        assertEquals(expected, operation.read(input("12"), Wrapped.class));
        var text = new StringWriter();
        operation.write(expected, text);
        assertEquals("12", text.toString());
        var bytes = new ByteArrayOutputStream();
        operation.writeCompact(expected, bytes);
        assertEquals("12", bytes.toString(StandardCharsets.UTF_8));
        assertEquals(expected, operation.convert(expected, Wrapped.class));
        assertEquals(12, operation.toTree(expected).asInt());
        assertEquals(expected, operation.read("12", org.jetlinks.json.JsonValue.class).as(Wrapped.class));
        JsonObject object = operation.read("{\"value\":12}", JsonObject.class);
        assertEquals(expected, object.get("value").as(Wrapped.class));
        assertEquals(expected, operation.createObject().put("value", 12).get("value").as(Wrapped.class));
    }

    @Test
    void operationCursorsAndIteratorsKeepAttributesAndTokenBoundaries() {
        JsonMapper operation = mapper(JsonOptions.DEFAULT, new AttributeModule())
                .withOptions(JsonOperationOptions.builder().attribute(OFFSET, 10).build());
        try (JsonReader reader = operation.reader(input("[12,null,13]"))) {
            assertEquals(10, reader.attribute(OFFSET));
            assertEquals(JsonToken.START_ARRAY, reader.next());
            reader.next();
            assertEquals(new Wrapped(new Adjusted(2)), reader.readValue(Wrapped.class));
            assertEquals(10, reader.attribute(OFFSET));
            assertNull(reader.current());
            assertEquals(JsonToken.NULL, reader.next());
            assertNull(reader.readValue(Adjusted.class));
            reader.next();
            assertEquals(new Wrapped(new Adjusted(3)), reader.readValue(Wrapped.class));
            assertEquals(10, reader.attribute(OFFSET));
            assertEquals(JsonToken.END_ARRAY, reader.next());
        }
        try (var iterator = operation.iterateArray(input("[12,13]"), Wrapped.class)) {
            assertEquals(new Wrapped(new Adjusted(2)), iterator.next());
            assertEquals(new Wrapped(new Adjusted(3)), iterator.next());
            assertFalse(iterator.hasNext());
        }
        var output = new ByteArrayOutputStream();
        try (JsonWriter writer = operation.writer(output)) {
            assertEquals(10, writer.attribute(OFFSET));
            writer.beginArray();
            writer.value(new Wrapped(new Adjusted(2)));
            writer.endArray();
        }
        assertEquals("[12]", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void operationIncrementalBindingPreservesAttributesAndReadFlags() {
        JsonMapper operation = mapper(JsonOptions.DEFAULT, new AttributeModule())
                .withOptions(JsonOperationOptions.builder().attribute(OFFSET, 10).failDuplicateKeys(true).build());
        try (var session = operation.readSession(Wrapped.class,
                JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
            session.feed(ByteBuffer.wrap("[12,13]".getBytes(StandardCharsets.UTF_8)));
            session.endInput();
            assertEquals(new Wrapped(new Adjusted(2)), session.read().value());
            assertEquals(new Wrapped(new Adjusted(3)), session.read().value());
            assertEquals(JsonReadSession.State.END, session.read().state());
        }
        try (var session = operation.readSession(Object.class,
                JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE))) {
            session.feed(ByteBuffer.wrap("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
            session.endInput();
            assertThrows(JsonException.class, session::read);
        }
    }

    @Test
    void preparedVariantsAreConcurrentAndDoNotChangeTheirBase()
            throws InterruptedException, java.util.concurrent.ExecutionException {
        JsonMapper base = mapper(JsonOptions.DEFAULT, new AttributeModule());
        JsonMapper first = base.withOptions(JsonOperationOptions.builder().attribute(OFFSET, 10).omitNulls(true).build());
        JsonMapper second = base.withOptions(JsonOperationOptions.builder().attribute(OFFSET, 20).pretty(true).build());
        var pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (JsonMapper mapper : List.of(base, first, second)) {
                tasks.add(pool.submit(() -> {
                    Wrapped expected = new Wrapped(new Adjusted(7));
                    for (int i = 0; i < 200; i++) {
                        assertEquals(expected, mapper.read(mapper.write(expected), Wrapped.class));
                        assertEquals(expected, mapper.convert(expected, Wrapped.class));
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("7", base.write(new Adjusted(7)));
    }

    private static Map<String, Object> mapOfNull() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("null", null);
        return map;
    }

    @Test
    void preparedOperationsRetainSingleRootAndCallerResourceContracts() {
        JsonMapper operation = mapper(JsonOptions.DEFAULT).withOptions(
                JsonOperationOptions.builder().pretty(true).build());
        assertThrows(JsonException.class, () -> operation.read("1 2", Integer.class));
        assertThrows(JsonException.class, () -> operation.read("", Integer.class));
        var input = new CallerInput();
        assertEquals(1, operation.read(input, Integer.class));
        var output = new CallerOutput();
        operation.write(Map.of("a", 1), output);
        try (var writer = operation.compactWriter(output)) {
            writer.beginArray();
            writer.value(1);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("[1"));
    }

    private static ByteArrayInputStream input(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    public record Name(String name) { }
    public record Nullable(String missing, Name item) { }
    public record Adjusted(int value) { }
    public record Wrapped(Adjusted item) { }

    private static int offset(Integer value) {
        return value == null ? 0 : value;
    }

    private static final class AttributeModule implements JsonModule {
        @Override
        public String id() {
            return "operation-attributes";
        }

        @Override
        public void configure(JsonModuleContext context) {
            context.add(Adjusted.class, new AdjustedAdapter());
            context.add(Wrapped.class, new WrappedAdapter());
        }
    }

    private static final class AdjustedAdapter implements JsonAdapter<Adjusted> {
        @Override
        public void write(Adjusted value, JsonWriter writer) {
            writer.value(value.value() + offset(writer.attribute(OFFSET)));
        }

        @Override
        public Adjusted read(JsonReader reader) {
            return new Adjusted(reader.readValue(Integer.class) - offset(reader.attribute(OFFSET)));
        }
    }

    private static final class WrappedAdapter implements JsonAdapter<Wrapped> {
        @Override
        public void write(Wrapped value, JsonWriter writer) {
            writer.value(value.item());
        }

        @Override
        public Wrapped read(JsonReader reader) {
            return new Wrapped(reader.readValue(Adjusted.class));
        }
    }

    private static final class CallerInput extends ByteArrayInputStream {
        private CallerInput() {
            super("1".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            fail("caller input closed");
        }
    }

    private static final class CallerOutput extends ByteArrayOutputStream {
        @Override
        public void close() {
            fail("caller output closed");
        }
    }
}
