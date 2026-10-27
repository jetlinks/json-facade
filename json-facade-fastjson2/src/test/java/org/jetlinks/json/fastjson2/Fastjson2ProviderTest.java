package org.jetlinks.json.fastjson2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.annotation.JSONField;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonAttribute;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fastjson2ProviderTest {
    private static JsonMapper mapper() {
        return Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions()).build();
    }

    @Test
    void discoveryRequiresExplicitNativeTokenLimits() {
        assertEquals("fastjson2", new Fastjson2Provider().id());
        configuration(() -> Json.builder().provider("fastjson2").build());
        assertEquals(Integer.MAX_VALUE, mapper().options().maxStringLength());
        assertEquals(Integer.MAX_VALUE, mapper().options().maxNumberLength());
        configuration(() -> Json.builder().provider("fastjson2")
                .options(Fastjson2Provider.nativeOptions().withMaxStringLength(10)).build());
        configuration(() -> Json.builder().provider("fastjson2")
                .options(Fastjson2Provider.nativeOptions().withMaxNumberLength(10)).build());
        configuration(() -> Json.builder().provider("fastjson2")
                .options(Fastjson2Provider.nativeOptions().withFailDuplicateKeys(true)).build());
        configuration(() -> mapper().withOptions(JsonOperationOptions.builder().failDuplicateKeys(true).build()));
        configuration(() -> mapper().withOptions(JsonOperationOptions.builder().failUnknownProperties(true).build()));
        configuration(() -> Json.builder().provider("fastjson2")
                .options(Fastjson2Provider.nativeOptions().withFailUnknownProperties(true)).build());
    }

    @Test
    void nativeBindingSupportsGenericsAllInputsAndCompleteRoots() {
        JsonMapper json = mapper();
        String input = "[{\"x\":1.00000000000000000001}]";
        JsonType<List<Map<String, BigDecimal>>> type = new JsonType<>() { };
        var expected = List.of(Map.of("x", new BigDecimal("1.00000000000000000001")));
        assertEquals(expected, json.read(input, type));
        assertEquals(expected, json.read(input.getBytes(StandardCharsets.UTF_8), type));
        assertEquals(expected, json.read(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), type));
        assertEquals(expected, json.read(new StringReader(input), type));
        assertInstanceOf(BigInteger.class, json.read("12345678901234567890123456789", Object.class));
        assertNull(json.read("null", Object.class));
        for (String invalid : List.of("", " ", "1 2", "{}[]", "{", "[1")) {
            assertThrows(JsonException.class, () -> json.read(invalid, Object.class));
        }
        // 语法宽松度由原生实现决定；不以手工扫描叠加第二套语法策略。
        for (String extension : List.of("1,", "[1,]", "{'x':1}")) {
            assertEquals(com.alibaba.fastjson2.JSON.parseObject(extension, Object.class), json.read(extension, Object.class));
        }
        assertThrows(NullPointerException.class, () -> json.read((String) null, type));
        assertThrows(NullPointerException.class, () -> json.read((byte[]) null, type));
        assertThrows(NullPointerException.class, () -> json.read((InputStream) null, type));
        assertThrows(NullPointerException.class, () -> json.read((Reader) null, type));
        assertThrows(NullPointerException.class, () -> json.read("1", (JsonType<?>) null));
        assertThrows(NullPointerException.class, () -> json.read(new byte[0], (JsonType<?>) null));
        assertThrows(NullPointerException.class, () -> json.read(new ByteArrayInputStream(new byte[0]), (JsonType<?>) null));
        assertThrows(NullPointerException.class, () -> json.read(new StringReader(""), (JsonType<?>) null));
    }

    @Test
    void optionsArePreparedAndDoNotMutateTheBaseOrWarmCaches() {
        JsonMapper base = mapper();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("x", null);
        assertEquals("{\"x\":null}", base.write(values));
        assertSame(base, base.withOptions(JsonOperationOptions.EMPTY));
        assertThrows(NullPointerException.class, () -> base.withOptions(null));
        JsonMapper changed = base.withOptions(JsonOperationOptions.builder().omitNulls(true)
                .losslessNumbers(false).pretty(true).build());
        assertEquals(Map.of(), changed.read(changed.write(values), Map.class));
        assertEquals("{\"x\":null}", base.write(values));
        assertInstanceOf(Double.class, changed.read("1.25", Object.class));
        assertInstanceOf(BigDecimal.class, base.read("1.25", Object.class));
        assertEquals(1, changed.read("{\"value\":1,\"extra\":2}", Item.class).value());
        assertEquals(1, base.read("{\"value\":1,\"extra\":2}", Item.class).value());
        JsonMapper restored = changed.withOptions(JsonOperationOptions.builder().omitNulls(false)
                .losslessNumbers(true).failUnknownProperties(false).pretty(false).failDuplicateKeys(false).build());
        assertEquals(base.options(), restored.options());
        assertEquals(base.write(values), restored.write(values));
        assertTrue(changed.write(Map.of("x", 1)).contains("\n"));
        ByteArrayOutputStream compact = new ByteArrayOutputStream();
        changed.writeCompact(Map.of("x", 1), compact);
        assertEquals("{\"x\":1}", compact.toString(StandardCharsets.UTF_8));
        assertTrue(changed.write(Map.of("x", 1)).contains("\n"));
        assertEquals(Collections.singletonList(null), changed.read(changed.write(Collections.singletonList(null)), List.class));
    }

    @Test
    void nativeExtensionsApplyToConversionsAndNestedTrees() {
        AtomicInteger callbacks = new AtomicInteger();
        JsonMapper json = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class, builder -> {
                    callbacks.incrementAndGet();
                    builder.reader().getProvider().register(Label.class, new ObjectReader<Label>() {
                        @Override
                        public Label readObject(JSONReader input, Type type, Object name, long features) {
                            return new Label(input.readString().substring(2));
                        }
                    });
                    builder.writer().getProvider().register(Label.class,
                            (output, value, name, type, features) -> output.writeString("L:" + ((Label) value).value()));
                }).build();
        assertEquals(1, callbacks.get());
        assertEquals(new Label("a"), json.read("\"L:a\"", Label.class));
        assertEquals("\"L:a\"", json.write(new Label("a")));
        assertEquals("L:a", json.convert(new Label("a"), String.class));
        assertEquals(new Label("a"), json.convert("L:a", Label.class));
        JsonObject tree = json.toTree(Map.of("label", new Label("a"))).asObject();
        assertEquals("L:a", tree.get("label").asString());
        JsonMapper operation = json.withOptions(JsonOperationOptions.builder().omitNulls(true).build());
        int prepared = callbacks.get();
        for (int i = 0; i < 5; i++) {
            assertEquals("L:a", operation.toTree(Map.of("label", new Label("a"))).get("label").asString());
            assertEquals(new Label("a"), operation.read("\"L:a\"", Label.class));
        }
        assertEquals(prepared, callbacks.get());
        assertEquals("\"L:a\"", json.write(new Label("a")));
    }

    @Test
    void neutralTreesKeepTheirOperationOwnerAndIsolateContainers() {
        JsonMapper base = literalTrees(mapper());
        JsonMapper operation = base.withOptions(JsonOperationOptions.builder().omitNulls(true).build());
        JsonObject object = operation.read("{\"x\":1,\"absent\":null}", JsonObject.class);
        JsonArray array = operation.read("[1,null]", JsonArray.class);
        assertEquals("{\"x\":1}", operation.write(object));
        assertEquals(Map.of("x", 1), object.as(Map.class));
        assertEquals(1, array.get(0).asInt());
        assertSame(JsonValue.NULL, operation.read("null", JsonValue.class));
        assertTrue(operation.read("null", JsonObject.class).isNull());
        assertTrue(operation.read("null", JsonArray.class).isNull());
        assertThrows(JsonException.class, () -> operation.read("[]", JsonObject.class));
        TreeHolder holder = operation.read("{\"object\":{\"x\":null},\"array\":[1]}", TreeHolder.class);
        assertEquals(Map.of(), holder.object().as(Map.class));
        assertEquals(Map.of("object", Map.of(), "array", List.of(1)), operation.read(operation.write(holder), Map.class));
        assertEquals(1, operation.convert(holder, TreeHolder.class).array().get(0).asInt());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("rows", new ArrayList<>(List.of(1, 2)));
        JsonObject copied = operation.createObject(source);
        ((List<?>) source.get("rows")).clear();
        assertEquals(2, copied.get("rows").size());
        JsonObject borrowed = operation.wrap(source);
        source.put("later", 3);
        assertEquals(3, borrowed.get("later").asInt());
        assertThrows(UnsupportedOperationException.class, () -> borrowed.put("x", 1));
        assertEquals(List.of(1), base.readTree("[1]").as(List.class));
    }

    @Test
    void resolvedReferencesAreCopiedOnlyAfterWholeRootBinding() {
        JsonMapper json = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class,
                        b -> b.writer().config(JSONWriter.Feature.ReferenceDetection)).build();
        Map<String, Object> child = Map.of("n", 1);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", child);
        source.put("b", child);
        String input = json.write(source);
        assertTrue(input.contains("$ref"));
        Map<?, ?> resolved = json.read(input, Map.class);
        assertSame(resolved.get("a"), resolved.get("b"));
        JsonValue tree = json.readTree(input);
        assertEquals(1, tree.get("a").get("n").asInt());
        assertEquals(1, tree.get("b").get("n").asInt());
        tree.get("a").asObject().put("n", 2);
        assertEquals(1, tree.get("b").get("n").asInt());
        String array = "[{\"n\":1},{\"$ref\":\"$[0]\"}]";
        assertEquals(1, json.readTree(array).get(1).get("n").asInt());
        String cycle = "{\"a\":{\"$ref\":\"$\"}}";
        assertEquals(JsonException.Kind.LIMIT, assertThrows(JsonException.class, () -> json.readTree(cycle)).kind());
        configuration(() -> json.read(input, JsonValue.class));
        configuration(() -> json.read(input.getBytes(StandardCharsets.UTF_8), JsonObject.class));
        configuration(() -> json.read(new StringReader(array), JsonArray.class));
        configuration(() -> json.read(new ByteArrayInputStream(cycle.getBytes(StandardCharsets.UTF_8)), JsonValue.class));
        assertThrows(JsonException.class, () -> json.read(
                "{\"tree\":{\"a\":{\"n\":1},\"b\":{\"$ref\":\"$.tree.a\"}}}", TreeValueHolder.class));
        assertThrows(JsonException.class, () -> json.read(array, JsonType.listOf(JsonType.of(JsonValue.class))));
    }

    @Test
    void directNeutralTreesPreserveLiteralReferencesWithNativeDetectionDisabled() {
        JsonMapper base = mapper();
        JsonMapper literal = literalTrees(base);
        String object = "{\"a\":{\"n\":1},\"b\":{\"$ref\":\"$.a\"}}";
        JsonValue tree = literal.read(object, JsonValue.class);
        assertEquals("$.a", tree.get("b").get("$ref").asString());
        assertEquals(1, tree.get("a").get("n").asInt());
        assertEquals("$.a", literal.read(object, JsonObject.class).get("b").get("$ref").asString());
        String array = "[{\"n\":1},{\"$ref\":\"$[0]\"}]";
        assertEquals("$[0]", literal.read(array, JsonArray.class).get(1).get("$ref").asString());
        String nested = "{\"tree\":{\"a\":{\"n\":1},\"b\":{\"$ref\":\"$.tree.a\"}}}";
        assertEquals("$.tree.a", literal.read(nested, TreeValueHolder.class).tree().get("b").get("$ref").asString());
        assertEquals("$[0]", literal.read(array, JsonType.listOf(JsonType.of(JsonValue.class)))
                .get(1).get("$ref").asString());
        assertEquals("$", literal.read("{\"a\":{\"$ref\":\"$\"}}", JsonValue.class).get("a").get("$ref").asString());
        assertEquals(object, literal.write(tree));
        assertEquals(1, base.readTree(object).get("b").get("n").asInt());
        configuration(() -> base.read(object, JsonValue.class));
        JsonMapper restored = literal.withOptions(JsonOperationOptions.builder()
                .nativeReader(JSONReader.Context.class, context -> {
                    context.config(JSONReader.Feature.DisableReferenceDetect, false);
                    return context;
                }).build());
        configuration(() -> restored.read(object, JsonValue.class));
        assertEquals("$.a", literal.read(object, JsonValue.class).get("b").get("$ref").asString());
    }

    private static JsonMapper literalTrees(JsonMapper mapper) {
        return mapper.withOptions(JsonOperationOptions.builder().nativeReader(JSONReader.Context.class, context -> {
            context.config(JSONReader.Feature.DisableReferenceDetect);
            return context;
        }).build());
    }

    @Test
    void nativeOperationCallbacksRunOnlyAtPreparationAndCanBeReplayed() {
        AtomicInteger callbacks = new AtomicInteger();
        JsonMapper base = mapper();
        JsonMapper changed = base.withOptions(JsonOperationOptions.builder()
                .nativeReader(JSONReader.Context.class, context -> {
                    callbacks.incrementAndGet();
                    return context;
                })
                .nativeWriter(JSONWriter.Context.class, context -> {
                    callbacks.incrementAndGet();
                    return context;
                })
                .build());
        assertEquals(2, callbacks.get());
        assertEquals(1, changed.read("1", Integer.class));
        assertEquals("1", changed.write(1));
        assertEquals(1, changed.convert(1, Integer.class));
        assertEquals(2, callbacks.get());
        changed.withOptions(JsonOperationOptions.builder().omitNulls(true).build());
        assertEquals(4, callbacks.get());
        AtomicInteger partial = new AtomicInteger();
        configuration(() -> base.withOptions(JsonOperationOptions.builder()
                .nativeReader(JSONReader.Context.class, context -> {
                    partial.incrementAndGet();
                    return context;
                })
                .nativeWriter(String.class, value -> value).build()));
        assertEquals(0, partial.get());
        configuration(() -> base.withOptions(JsonOperationOptions.builder()
                .nativeReader(JSONReader.Context.class, context -> null).build()));
        configuration(() -> base.withOptions(JsonOperationOptions.builder()
                .nativeWriter(JSONWriter.Context.class, context -> new JSONWriter.Context(new ObjectWriterProvider())).build()));
        configuration(() -> base.withOptions(JsonOperationOptions.builder()
                .nativeReader(JSONReader.Context.class, context -> new JSONReader.Context(new ObjectReaderProvider())).build()));
    }

    @Test
    void conflictingNativePoliciesFailBeforeOperations() {
        List<Consumer<Fastjson2Builder>> conflicts = List.of(
                b -> b.reader().setMaxLevel(1), b -> b.writer().setMaxLevel(1),
                b -> b.reader().config(JSONReader.Feature.UseDoubleForDecimals),
                b -> b.reader().config(JSONReader.Feature.ErrorOnUnknownProperties),
                b -> b.reader().config(JSONReader.Feature.ErrorOnDuplicateKeys),
                b -> b.reader().config(JSONReader.Feature.IgnoreCheckClose),
                b -> b.writer().config(JSONWriter.Feature.WriteNulls, false),
                b -> b.writer().config(JSONWriter.Feature.PrettyFormat));
        for (Consumer<Fastjson2Builder> conflict : conflicts) {
            configuration(() -> Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                    .configureNative(Fastjson2Builder.class, conflict).build());
        }
        configuration(() -> Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(String.class, value -> { }).build());
        configuration(() -> Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class, builder -> { throw new IllegalStateException("failure"); }).build());
    }

    @Test
    void unsupportedCapabilitiesRejectBeforeTouchingCallerIo() {
        JsonMapper json = mapper();
        InputStream input = new InputStream() {
            @Override
            public int read() { throw new AssertionError("unexpected read"); }
        };
        OutputStream output = new OutputStream() {
            @Override
            public void write(int value) { throw new AssertionError("unexpected write"); }
        };
        configuration(() -> json.reader(input));
        configuration(() -> json.iterateArray(input, Integer.class));
        configuration(() -> json.writer(output));
        configuration(() -> json.compactWriter(output));
        configuration(json::eventSession);
        configuration(() -> json.readSession(Integer.class, JsonReadOptions.of(JsonReadOptions.Mode.NDJSON)));
        configuration(() -> json.withOptions(JsonOperationOptions.builder()
                .attribute(JsonAttribute.of("test", Integer.class), 1).build()));
        configuration(() -> Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .module(new JsonModule() {
                    @Override
                    public String id() { return "unsupported"; }
                    @Override
                    public void configure(JsonModuleContext context) { throw new AssertionError("unexpected callback"); }
                }).build());
        assertThrows(NullPointerException.class, () -> json.reader(null));
        assertThrows(NullPointerException.class, () -> json.iterateArray(input, (JsonType<?>) null));
        assertThrows(NullPointerException.class, () -> json.iterateArray(null, Integer.class));
        assertThrows(NullPointerException.class, () -> json.writer(null));
        assertThrows(NullPointerException.class, () -> json.compactWriter(null));
        assertThrows(NullPointerException.class, () -> json.readSession((JsonType<?>) null, JsonReadOptions.of(JsonReadOptions.Mode.NDJSON)));
        assertThrows(NullPointerException.class, () -> json.readSession(Integer.class, null));
    }

    @Test
    void callerResourcesStayOpenAndIoFailuresKeepTheirCause() {
        JsonMapper json = mapper();
        AtomicInteger closes = new AtomicInteger();
        InputStream input = new ByteArrayInputStream("1".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        Reader reader = new StringReader("1") {
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        OutputStream output = new ByteArrayOutputStream() {
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        Writer writer = new StringWriter() {
            @Override
            public void close() { closes.incrementAndGet(); }
        };
        assertEquals(1, json.read(input, Integer.class));
        assertEquals(1, json.read(reader, Integer.class));
        json.write(1, output);
        json.writeCompact(2, output);
        json.write(1, writer);
        assertEquals(0, closes.get());
        IOException cause = new IOException("sentinel");
        io(() -> json.read(new InputStream() {
            @Override
            public int read() throws IOException { throw cause; }
        }, Object.class), cause);
        io(() -> json.read(new Reader() {
            @Override
            public int read(char[] buffer, int start, int length) throws IOException { throw cause; }
            @Override
            public void close() { closes.incrementAndGet(); }
        }, Object.class), cause);
        io(() -> json.write(1, new OutputStream() {
            @Override
            public void write(int value) throws IOException { throw cause; }
        }), cause);
        io(() -> json.write(1, new Writer() {
            @Override
            public void write(char[] buffer, int start, int length) throws IOException { throw cause; }
            @Override
            public void flush() { }
            @Override
            public void close() { closes.incrementAndGet(); }
        }), cause);
        assertThrows(NullPointerException.class, () -> json.write(1, (OutputStream) null));
        assertThrows(NullPointerException.class, () -> json.writeCompact(1, null));
        assertThrows(NullPointerException.class, () -> json.write(1, (Writer) null));
        assertThrows(NullPointerException.class, () -> json.convert(1, (JsonType<?>) null));
    }

    @Test
    void referencesAndDepthRemainNativeOperationLocal() {
        JsonMapper json = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class,
                        b -> b.writer().config(JSONWriter.Feature.ReferenceDetection)).build();
        Node a = new Node();
        Node b = new Node();
        a.name = "a";
        b.name = "b";
        a.next = b;
        b.next = a;
        Node read = json.read(json.write(a), Node.class);
        assertSame(read, read.next.next);
        Node converted = json.convert(a, Node.class);
        assertSame(converted, converted.next.next);
        assertNotSame(read, converted);
        assertSame(read, read.next.next);
        JsonMapper shallow = Json.builder().provider("fastjson2")
                .options(Fastjson2Provider.nativeOptions().withMaxDepth(2)).build();
        assertThrows(JsonException.class, () -> shallow.read("[[[1]]]", Object.class));
        assertThrows(JsonException.class, () -> shallow.write(List.of(List.of(List.of(1)))));
        assertEquals(3, json.read(json.write(new NativeName(3)), NativeName.class).value());
        assertTrue(json.write(new NativeName(3)).contains("native_value"));
    }

    @Test
    void nativeCustomFailuresAndConcurrentVariantsStayIsolated() throws Exception {
        JsonMapper failing = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                .configureNative(Fastjson2Builder.class, builder -> builder.writer().getProvider().register(Label.class,
                        (output, value, name, type, features) -> { throw new IllegalStateException("native failure"); })).build();
        assertEquals(JsonException.Kind.BINDING,
                assertThrows(JsonException.class, () -> failing.write(new Label("x"))).kind());
        JsonMapper base = mapper();
        JsonMapper changed = base.withOptions(JsonOperationOptions.builder().omitNulls(true).pretty(true).build());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 64; i++) {
                int id = i;
                tasks.add(() -> {
                    Item item = new Item(id);
                    assertEquals(item, base.read(base.write(item), Item.class));
                    assertEquals(item, changed.convert(item, Item.class));
                    assertEquals("{\"x\":null}", base.write(Collections.singletonMap("x", null)));
                    assertEquals(Map.of(), changed.read(changed.write(Collections.singletonMap("x", null)), Map.class));
                    ByteArrayOutputStream compact = new ByteArrayOutputStream();
                    changed.writeCompact(Map.of("x", id), compact);
                    assertFalse(compact.toString(StandardCharsets.UTF_8).contains("\n"));
                    return null;
                });
            }
            for (var task : executor.invokeAll(tasks)) { task.get(20, TimeUnit.SECONDS); }
        } finally {
            executor.shutdownNow();
        }
        assertArrayEquals("1".getBytes(StandardCharsets.UTF_8), base.writeBytes(1));
    }

    @Test
    void conversionAndTreesPreserveJsonRepresentationsForDatesBinaryAndNestedValues() {
        JsonMapper json = mapper();
        List<Object> values = List.of(new java.util.Date(0), java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDateTime.of(2026, 1, 1, 2, 3), new byte[]{1, 2},
                Map.of("date", new java.util.Date(0), "bytes", new byte[]{1, 2}),
                new BigDecimal("1.2300"), new char[]{'a', 'b'});
        for (JsonMapper variant : List.of(json,
                json.withOptions(JsonOperationOptions.builder().pretty(true).build()))) {
            for (Object value : values) {
                Object expected = variant.read(variant.writeBytes(value), Object.class);
                assertEquals(expected, variant.convert(value, Object.class));
                assertEquals(expected, variant.toTree(value).toJavaValue());
            }
        }
    }

    private static void configuration(Runnable action) {
        assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class, action::run).kind());
    }

    private static void io(Runnable action, IOException expected) {
        JsonException failure = assertThrows(JsonException.class, action::run);
        assertEquals(JsonException.Kind.IO, failure.kind());
        Throwable cause = failure;
        while (cause.getCause() != null) { cause = cause.getCause(); }
        assertSame(expected, cause);
    }

    public record Item(int value) { }
    public record Label(String value) { }
    public record TreeHolder(JsonObject object, JsonArray array) { }
    public record TreeValueHolder(JsonValue tree) { }
    public record NativeName(@JSONField(name = "native_value") int value) { }
    public static class Node {
        public String name;
        public Node next;
    }
}
