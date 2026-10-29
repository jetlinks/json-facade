package org.jetlinks.json.compat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONAware;
import com.alibaba.fastjson.JSONStreamAware;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeFilter;
import com.alibaba.fastjson.serializer.PropertyFilter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonPointer;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.fastjson2.Fastjson2Provider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仅此测试主动使用弃用入口，验证真实迁移流程，不隐藏生产代码告警。 */
@SuppressWarnings("deprecation")
class CompatibilityTest {
    @BeforeAll
    static void configureDefault() {
        String provider = System.getProperty("json.compat.provider", "jackson2");
        JsonMapper configured = mapper(provider);
        Json.registerGlobal(configured.withOptions(JsonOperationOptions.builder()
                .losslessNumbers(false).failUnknownProperties(!provider.equals("fastjson2")).build()));
    }

    private static JsonMapper mapper(String provider) {
        var builder = Json.builder().provider(provider);
        if (provider.equals("fastjson2")) {
            builder.options(Fastjson2Provider.nativeOptions());
        }
        return builder.build();
    }

    @Test
    void nestedModificationAndNeutralAssignmentUseTheSameStorageWithEveryProvider() {
        for (String provider : List.of("jackson2", "jackson3", "fastjson2")) {
            JsonMapper mapper = mapper(provider);
            JSONObject model = JSON.parseObject(mapper, "{\"flows\":[{\"type\":\"node\",\"id\":1}]}");
            JsonObject neutral = model;
            Map<String, Object> map = neutral;
            JSONArray flows = model.getJSONArray("flows");
            JsonArray neutralArray = flows;
            List<Object> list = neutralArray;
            JSONObject node = (JSONObject) list.get(0);
            node.put("z", "flow-1");
            JSONObject tab = new JSONObject(mapper);
            flows.add(0, tab);
            tab.put("type", "tab");
            tab.put("label", "test");
            flows.stream().map(JSONObject.class::cast).forEach(item -> item.put("checked", true));
            String text = model.toJSONString();
            Map<?, ?> result = mapper.read(text, Map.class);
            assertEquals(map, result);
            assertSame(tab, flows.get(0));
            assertEquals("flow-1", model.getJSONArray("flows").getJSONObject(1).getString("z"));
            assertTrue(neutralArray.getObject(0).node("checked").asBoolean());
            assertEquals(text, mapper.write(model));
            JSONObject copy = model.deepCopy();
            tab.put("label", "changed");
            assertEquals("test", copy.getJSONArray("flows").getJSONObject(0).getString("label"));
            JSONArray arrayCopy = flows.deepCopy();
            flows.clear();
            assertEquals(2, arrayCopy.size());
            assertEquals("flow-1", ((JSONObject) arrayCopy.toArray()[1]).getString("z"));
            assertNull(JSON.parseObject(mapper, "null"));
            assertNull(JSON.parseArray(mapper, "null"));
            assertEquals(1, JSON.parseObject(mapper, "{\"value\":1}", Item.class).value());
            assertEquals(1, JSON.parseObject(mapper, "{\"value\":1}", JSONObject.class).getIntValue("value"));
            assertEquals(1, JSON.parseObject(mapper, "[1]", JSONArray.class).getIntValue(0));
            assertEquals(1, JSON.parse(mapper, "1"));
            assertInstanceOf(JSONArray.class, JSON.parse(mapper, "[1]"));
            assertEquals("java.lang.Runtime", JSON.parseObject(mapper,
                    "{\"@type\":\"java.lang.Runtime\"}").getString("@type"));
        }
    }

    @Test
    void staticHelpersDelegateAndDefaultConstructorsRetainMapListSemantics() {
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        object.put("items", array);
        array.add(1);
        assertSame(array, object.getJSONArray("items"));
        assertEquals(Map.of("items", List.of(1)), JSON.parse(JSON.toJSONString(object)));
        assertEquals(JSON.toJSONString(object), new String(JSON.toJSONBytes(object), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, JSON.parseObject("{\"value\":2}", Item.class).value());
        assertEquals(List.of(1, 2), JSON.parseArray("[1,2]", Integer.class));
        assertEquals(List.of(1), JSON.parseObject("[1]", JsonType.listOf(JsonType.of(Integer.class))));
        assertInstanceOf(JSONObject.class, JSON.toJSON(new Item(3)));
        assertEquals(3, JSON.toJavaObject(Map.of("value", 3), Item.class).value());
        assertEquals(3, new JSONObject(new LinkedHashMap<>(Map.of("value", 3))).toJavaObject(Item.class).value());
        assertEquals(List.of(1), new JSONArray(new ArrayList<>(List.of(1))).toJavaList(Integer.class));
        assertEquals(1, JSON.parseArray("[1]").getObject(0, Integer.class));
        JSONObject parsed = JSON.parseObject("{\"value\":4,\"nested\":{\"value\":5}}");
        assertEquals(4, parsed.getObject("value", Integer.class));
        assertEquals(5, ((JSONObject) parsed.entrySet().stream()
                .filter(entry -> entry.getKey().equals("nested")).findFirst().orElseThrow().getValue()).getIntValue("value"));
        parsed.entrySet().stream().filter(entry -> entry.getKey().equals("value"))
                .findFirst().orElseThrow().setValue(6);
        assertEquals(6, parsed.getIntValue("value"));
        assertTrue(parsed.values().contains(6));
        assertFalse(parsed.isNull());
        assertFalse(parsed.isMissing());
        assertEquals(Map.of("value", 6, "nested", Map.of("value", 5)), parsed.toJavaValue());
        assertEquals(6, parsed.node("value").asInt());
        assertEquals(1, array.node(0).asInt());
        assertTrue(array.node("no").isMissing());
        assertTrue(parsed.node(0).isMissing());
        assertFalse(array.isNull());
        assertFalse(array.isMissing());
        assertEquals(List.of(1), array.toJavaValue());
        assertEquals(List.of(1), array.as(JsonType.listOf(JsonType.of(Integer.class))));
    }

    @Test
    void legacyGettersHandleScalarsNullAndWrongShapes() {
        JSONObject object = JSON.parseObject("{\"n\":\"12\",\"flag\":true,\"nil\":null}");
        JSONArray array = JSON.parseArray("[\"12\",true,null,{},[]]");
        assertEquals("12", object.getString("n"));
        assertEquals(12, object.getInteger("n"));
        assertEquals(12L, object.getLong("n"));
        assertEquals(12D, object.getDouble("n"));
        assertEquals(12F, object.getFloat("n"));
        assertEquals((short) 12, object.getShort("n"));
        assertEquals((byte) 12, object.getByte("n"));
        assertTrue(object.getBoolean("flag"));
        assertTrue(object.getBooleanValue("flag"));
        assertEquals(12, object.getIntValue("n"));
        assertEquals(12L, object.getLongValue("n"));
        assertEquals(12D, object.getDoubleValue("n"));
        assertEquals(12F, object.getFloatValue("n"));
        assertEquals((short) 12, object.getShortValue("n"));
        assertEquals((byte) 12, object.getByteValue("n"));
        assertEquals(new java.math.BigDecimal("12"), object.getBigDecimal("n"));
        assertEquals(new java.math.BigInteger("12"), object.getBigInteger("n"));
        assertEquals("12", array.getString(0));
        assertEquals(12, array.getInteger(0));
        assertEquals(12L, array.getLong(0));
        assertEquals(12D, array.getDouble(0));
        assertEquals(12F, array.getFloat(0));
        assertEquals((short) 12, array.getShort(0));
        assertEquals((byte) 12, array.getByte(0));
        assertTrue(array.getBoolean(1));
        assertTrue(array.getBooleanValue(1));
        assertEquals(12, array.getIntValue(0));
        assertEquals(12L, array.getLongValue(0));
        assertEquals(12D, array.getDoubleValue(0));
        assertEquals(12F, array.getFloatValue(0));
        assertEquals((short) 12, array.getShortValue(0));
        assertEquals((byte) 12, array.getByteValue(0));
        assertEquals(object.getBigDecimal("n"), array.getBigDecimal(0));
        assertEquals(object.getBigInteger("n"), array.getBigInteger(0));
        assertNull(object.getJSONArray("nil"));
        assertNull(object.getJSONObject("missing"));
        assertNull(array.getJSONObject(2));
        assertNull(array.getJSONArray(2));
        assertThrows(JSONException.class, () -> object.getJSONArray("n"));
        assertThrows(JSONException.class, () -> array.getJSONObject(0));
        assertEquals(Map.of(), array.getJSONObject(3));
        assertEquals(List.of(), array.getJSONArray(4));
        assertEquals(Arrays.asList("12", true, null, Map.of(), List.of()), array);
        assertEquals(array, JSON.parse(array.toString()));
        assertNull(object.getInteger("nil"));
        assertEquals(0, object.getIntValue("missing"));
    }

    public record Item(int value) { }

    @Test
    void inheritedStaticAndInstanceOutputHaveTheOldSourceShape() throws IOException {
        JSON object = new JSONObject().fluentPut("value", 3);
        JSON array = new JSONArray().fluentAdd(object);
        assertEquals("{\"value\":3}", JSONObject.toJSONString(new Item(3)));
        assertEquals("[1]", JSONArray.toJSONString(List.of(1)));
        JSONAware aware = object;
        assertEquals(object.toString(), aware.toJSONString());
        StringBuilder chars = new StringBuilder();
        JSONStreamAware streamAware = array;
        streamAware.writeJSONString(chars);
        assertEquals(array.toString(), chars.toString());
        StringWriter writer = new StringWriter();
        streamAware.writeJSONString(writer);
        assertEquals(array.toString(), writer.toString());
        assertTrue(JSON.toJSONString(object, true).contains("\n"));
        assertTrue(object.toString(SerializerFeature.PrettyFormat).contains("\n"));
        assertFalse(JSON.toJSONString(object, false).contains("\n"));
        assertEquals(object.toString(), new String(JSON.toJSONBytes(object), StandardCharsets.UTF_8));
        StringWriter staticWriter = new StringWriter();
        JSON.writeJSONStringTo(object, staticWriter);
        assertEquals(object.toString(), staticWriter.toString());

        TrackingOutput bytes = new TrackingOutput();
        int size = JSON.writeJSONString(bytes, array);
        assertEquals(bytes.size(), size);
        assertEquals(array.toString(), bytes.toString(StandardCharsets.UTF_8));
        assertFalse(bytes.closed);
        TrackingInput input = new TrackingInput(bytes.toByteArray());
        JSONArray read = JSON.parseObject(input, JSONArray.class);
        assertEquals(array, read);
        assertFalse(input.closed);
        assertEquals(array, JSON.parseObject(bytes.toByteArray(), JSONArray.class));
        assertEquals(array, JSON.parse(bytes.toByteArray()));
    }

    @Test
    void nestedLegacyInterfacesKeepTheActiveProvidersSerializationContext() {
        for (String provider : List.of("jackson2", "jackson3", "fastjson2")) {
            JsonMapper mapper = mapper(provider);
            JsonMapper omitted = mapper.withOptions(JsonOperationOptions.builder().omitNulls(true).build());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nil", null);
            JSONObject child = new JSONObject(omitted, data);
            JSONArray array = new JSONArray(omitted).fluentAdd(child);
            JSONObject parent = new JSONObject(mapper).fluentPut("items", array);
            assertEquals("{}", child.toJSONString());
            assertEquals(Map.of("items", List.of(data)), mapper.read(parent.toJSONString(), Object.class));
            assertEquals(Map.of("items", List.of(data)), mapper.read(JSON.toJSONString(parent), Object.class));
        }
    }

    @Test
    void genericsBlankInputAndRootIdentityAreAdaptedWithoutNativeRegistries() {
        TypeReference<Map<String, List<Item>>> type = new TypeReference<>() { };
        Map<String, List<Item>> result = JSON.parseObject("{\"rows\":[{\"value\":2}]}", type);
        assertEquals(2, result.get("rows").get(0).value());
        assertEquals(result, JSON.parseObject("{\"rows\":[{\"value\":2}]}", type.getType()));
        JSONObject object = JSON.parseObject("{\"rows\":[{\"value\":2}]}");
        assertEquals(result, object.toJavaObject(type));
        assertEquals(result, object.toJavaObject(type.getType()));
        assertEquals(List.of(new Item(2)), object.getObject("rows", new TypeReference<List<Item>>() { }));
        assertEquals(List.of(new Item(2)), object.getObject("rows", JsonType.listOf(JsonType.of(Item.class)).type()));
        assertEquals(new Item(2), object.getJSONArray("rows").getObject(0, (java.lang.reflect.Type) Item.class));
        assertSame(object, JSON.toJSON(object));
        assertSame(object, object.at(JsonPointer.of("")));
        assertEquals(2, object.at(JsonPointer.of("/rows/0/value")).asInt());
        assertEquals("/rows/0/missing", object.at(JsonPointer.of("/rows/0/missing")).pointer().toString());
        for (String input : Arrays.asList(null, "", " \n\t")) {
            assertNull(JSON.parse(input));
            assertNull(JSON.parseObject(input));
            assertNull(JSON.parseArray(input));
            assertNull(JSON.parseObject(input, Item.class));
            assertNull(JSON.parseArray(input, Item.class));
        }
        assertNull(JSON.parseObject((byte[]) null, Item.class));
        assertNull(JSON.parseObject(new byte[0], Item.class));
        assertThrows(UnsupportedOperationException.class, () -> new TypeReference<List<Item>>(Item.class) { }.getType());
    }

    @Test
    void shallowCloneConstructorsAndFluentHelpersPreserveReferences() {
        Map<String, Object> child = new LinkedHashMap<>(Map.of("n", 1));
        for (JSONObject object : List.of(new JSONObject(2), new JSONObject(true), new JSONObject(2, true))) {
            object.fluentPut("child", child).fluentPutAll(Map.of("n", 2));
            JSONObject copy = object.clone();
            assertSame(child, copy.getInnerMap().get("child"));
            copy.put("n", 4);
            assertEquals(2, object.getIntValue("n"));
            copy.getJSONObject("child").put("n", 3);
            assertEquals(3, object.getJSONObject("child").getIntValue("n"));
            object.fluentRemove("n").fluentClear();
            assertTrue(object.isEmpty());
            copy.getInnerMap().put("extra", true);
            assertTrue(copy.getBooleanValue("extra"));
        }
        JSONArray array = new JSONArray(3).fluentAdd(child).fluentAdd(0, 1)
                .fluentAddAll(List.of(2)).fluentAddAll(1, List.of(3)).fluentSet(1, 4);
        JSONArray copy = array.clone();
        assertSame(child, copy.remove(2));
        array.fluentRemove(Integer.valueOf(4)).fluentRemove(0).fluentRemoveAll(List.of(2))
                .fluentRetainAll(List.of(child));
        assertEquals(List.of(child), array);
        array.fluentClear();
        assertTrue(array.isEmpty());
    }

    @Test
    void jdkViewsExposeLegacyContainersAndWriteThrough() {
        JSONObject object = JSON.parseObject("{\"one\":{\"n\":1},\"two\":2}");
        assertInstanceOf(JSONObject.class, object.values().iterator().next());
        assertTrue(object.values().remove(2));
        assertEquals(java.util.Set.of("one"), object.keySet());
        object.keySet().remove("one");
        assertTrue(object.isEmpty());
        JSONArray array = JSON.parseArray("[{\"n\":1},2,3]");
        assertInstanceOf(JSONObject.class, array.toArray(new Object[0])[0]);
        var iterator = array.listIterator(1);
        assertEquals(1, iterator.nextIndex());
        assertEquals(0, iterator.previousIndex());
        assertTrue(iterator.hasPrevious());
        assertInstanceOf(JSONObject.class, iterator.previous());
        iterator.set(Map.of("n", 4));
        iterator.next();
        iterator.add(5);
        assertEquals(2, iterator.next());
        iterator.remove();
        List<Object> subList = array.subList(0, 2);
        assertInstanceOf(JSONObject.class, subList.get(0));
        subList.set(1, 6);
        subList.remove(0);
        assertEquals(List.of(6, 3), array);
        assertTrue(array.containsAll(List.of(6, 3)));
        assertEquals(1, array.lastIndexOf(3));
        array.retainAll(List.of(6));
        assertEquals(List.of(6), array);
    }

    @Test
    void legacyBoundsDefaultsAndExceptionsRemainDistinctFromNeutralNodes() {
        JSONArray array = JSON.parseArray("[null,\"bad\"]");
        assertNull(array.getInteger(0));
        assertEquals(0, array.getIntValue(0));
        for (int index : List.of(-1, 2)) {
            assertThrows(IndexOutOfBoundsException.class, () -> array.getInteger(index));
            assertThrows(IndexOutOfBoundsException.class, () -> array.getIntValue(index));
            assertThrows(IndexOutOfBoundsException.class, () -> array.getObject(index, Item.class));
            assertTrue(array.node(index).isMissing());
        }
        JSONException parse = assertThrows(JSONException.class, () -> JSON.parseObject("{\"value\":?}"));
        assertInstanceOf(JsonException.class, parse.getCause());
        JSONException scalar = assertThrows(JSONException.class, () -> array.getInteger(1));
        assertInstanceOf(JsonException.class, scalar.getCause());
        assertThrows(JsonException.class, () -> array.node(1).asInt());
        assertThrows(JSONException.class, () -> JSON.parseObject("{\"value\":\"bad\"}", Item.class));
        assertThrows(JSONException.class, () -> JSON.writeJSONString(new java.io.Writer() {
            @Override
            public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("failed");
            }
            @Override
            public void flush() { }
            @Override
            public void close() { }
        }, new Item(1)));
    }

    @Test
    void nullScalarDefaultsAndProviderOwnedDateBinaryConversions() {
        JSONObject object = JSON.parseObject("{\"nil\":null}");
        JSONArray array = JSON.parseArray("[null]");
        for (String key : List.of("nil", "missing")) {
            assertEquals(0L, object.getLongValue(key));
            assertEquals(0D, object.getDoubleValue(key));
            assertEquals(0F, object.getFloatValue(key));
            assertEquals((short) 0, object.getShortValue(key));
            assertEquals((byte) 0, object.getByteValue(key));
            assertFalse(object.getBooleanValue(key));
        }
        assertEquals(0L, array.getLongValue(0));
        assertEquals(0D, array.getDoubleValue(0));
        assertEquals(0F, array.getFloatValue(0));
        assertEquals((short) 0, array.getShortValue(0));
        assertEquals((byte) 0, array.getByteValue(0));
        assertFalse(array.getBooleanValue(0));
        assertNull(object.getObject("nil", Item.class));
        assertNull(object.getObject("missing", Item.class));
        assertNull(array.getObject(0, Item.class));
        for (String provider : List.of("jackson2", "jackson3", "fastjson2")) {
            JsonMapper mapper = mapper(provider);
            JSONObject temporal = new JSONObject(mapper).fluentPut("epoch", 1234L).fluentPut("bytes", List.of(1, 2));
            JSONArray temporalArray = new JSONArray(mapper).fluentAdd(1234L).fluentAdd(List.of(1, 2));
            assertEquals(mapper.convert(1234L, java.util.Date.class), temporal.getDate("epoch"));
            assertEquals(temporal.getDate("epoch"), temporalArray.getDate(0));
            assertEquals(mapper.convert(1234L, java.sql.Date.class), temporal.getSqlDate("epoch"));
            assertEquals(temporal.getSqlDate("epoch"), temporalArray.getSqlDate(0));
            assertEquals(mapper.convert(1234L, java.sql.Timestamp.class), temporal.getTimestamp("epoch"));
            assertEquals(temporal.getTimestamp("epoch"), temporalArray.getTimestamp(0));
            org.junit.jupiter.api.Assertions.assertArrayEquals(mapper.convert(List.of(1, 2), byte[].class), temporal.getBytes("bytes"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(temporal.getBytes("bytes"), temporalArray.getBytes(1));
        }
    }

    @Test
    void supportedOptionsUseTheSelectedProvidersCommonOptions() {
        assertInstanceOf(Double.class, JSON.parse("1.25"));
        if (Json.global().options().failUnknownProperties()) {
            assertThrows(JSONException.class, () -> JSON.parseObject("{\"value\":3,\"other\":4}", Item.class));
        }
        Object decimal = JSON.parse("1.25", Feature.UseBigDecimal);
        assertInstanceOf(java.math.BigDecimal.class, decimal);
        assertEquals(3, JSON.parseObject("{\"value\":3,\"other\":4}", Item.class, Feature.IgnoreNotMatch).value());
        assertEquals(new java.math.BigDecimal("1.25"), JSON.parse("1.25", Feature.UseBigDecimal.mask));
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("nil", null);
        assertTrue(JSON.toJSONString(withNull, SerializerFeature.WriteMapNullValue).contains("\"nil\":null"));
        assertTrue(JSON.toJSONString(Map.of("n", 1), SerializerFeature.PrettyFormat.mask).contains("\n"));
        JsonMapper omitted = Json.global().withOptions(JsonOperationOptions.builder().omitNulls(true).build());
        JSONObject object = new JSONObject(omitted, withNull);
        assertEquals("{}", object.toJSONString());
        assertTrue(object.toString(SerializerFeature.WriteMapNullValue).contains("\"nil\":null"));
        assertEquals("{}", object.toJSONString());
        assertTrue(JSON.toJSONString(object, SerializerFeature.PrettyFormat, SerializerFeature.WriteMapNullValue)
                .contains("\"nil\""));
    }

    @Test
    void legacyFeatureMasksAndUnsupportedCaptureHaveNoHiddenState() throws IOException {
        int reader = Feature.config(0, Feature.UseBigDecimal, true);
        assertEquals(Feature.UseBigDecimal.getMask(), reader);
        assertTrue(Feature.isEnabled(reader, Feature.UseBigDecimal));
        assertFalse(Feature.isEnabled(Feature.config(reader, Feature.UseBigDecimal, false), Feature.UseBigDecimal));
        int writer = SerializerFeature.config(0, SerializerFeature.PrettyFormat, true);
        assertEquals(SerializerFeature.PrettyFormat.getMask(), writer);
        assertTrue(SerializerFeature.isEnabled(writer, SerializerFeature.PrettyFormat));
        assertTrue(SerializerFeature.isEnabled(0, writer, SerializerFeature.PrettyFormat));
        assertFalse(SerializerFeature.isEnabled(
                SerializerFeature.config(writer, SerializerFeature.PrettyFormat, false), SerializerFeature.PrettyFormat));
        assertNull(JSON.parseObject((java.io.InputStream) null, Item.class));
        class Intermediate<T> extends TypeReference<T> { }
        assertThrows(UnsupportedOperationException.class, () -> new Intermediate<Item>() { }.getType());
        class Concrete extends TypeReference<Item> { }
        assertThrows(UnsupportedOperationException.class, () -> new Concrete() { }.getType());
        assertEquals(JsonType.listOf(JsonType.of(String.class)).type(), TypeReference.LIST_STRING);
    }

    @Test
    void everyUnsupportedOptionFailsBeforeInputOutputOrConversion() {
        for (Feature feature : Feature.values()) {
            if (feature == Feature.UseBigDecimal || feature == Feature.IgnoreNotMatch) {
                continue;
            }
            TrackingInput input = new TrackingInput("{\"value\":1}".getBytes(StandardCharsets.UTF_8));
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> JSON.parseObject(input, Item.class, feature));
            assertTrue(error.getMessage().contains(feature.name()));
            assertEquals(0, input.reads);
            assertFalse(input.closed);
            assertThrows(UnsupportedOperationException.class, () -> JSON.parse("null", feature.mask));
        }
        for (SerializerFeature feature : SerializerFeature.values()) {
            if (feature == SerializerFeature.PrettyFormat || feature == SerializerFeature.WriteMapNullValue) {
                continue;
            }
            TrackingOutput output = new TrackingOutput();
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> JSON.writeJSONString(output, new PoisonBean(), feature));
            assertTrue(error.getMessage().contains(feature.name()));
            assertEquals(0, output.size());
            assertFalse(output.closed);
            assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONString(new PoisonBean(), feature.mask));
        }
        assertThrows(UnsupportedOperationException.class, () -> JSON.parse("null", Integer.MIN_VALUE));
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONString(new PoisonBean(), 1 << 30));
        assertThrows(UnsupportedOperationException.class, () -> JSON.parseObject("{}", Item.class,
                (ParserConfig) null));
        assertThrows(UnsupportedOperationException.class, () -> JSON.parse("{}", (ParserConfig) null));
        assertThrows(UnsupportedOperationException.class, ParserConfig::new);
        assertThrows(UnsupportedOperationException.class, ParserConfig::getGlobalInstance);
        assertThrows(UnsupportedOperationException.class, SerializeConfig::new);
        assertThrows(UnsupportedOperationException.class, SerializeConfig::getGlobalInstance);
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONString(new PoisonBean(), (SerializeConfig) null));
        PropertyFilter filter = (bean, name, item) -> {
            throw new AssertionError("must not execute");
        };
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONString(new PoisonBean(), filter));
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONString(new PoisonBean(), new SerializeFilter[]{filter}));
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONBytes(new PoisonBean(), filter));
        assertThrows(UnsupportedOperationException.class, () -> JSON.toJSONStringWithDateFormat(new PoisonBean(), "yyyy"));
        assertThrows(UnsupportedOperationException.class, () -> JSON.setDefaultTypeKey("kind"));
    }

    @Test
    void legacyDeclarationsCarryDeprecationWhileNeutralAndJdkContractsDoNot() throws NoSuchMethodException {
        for (Class<?> type : List.of(JSON.class, JSONObject.class, JSONArray.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                boolean neutral = java.util.stream.Stream.of(org.jetlinks.json.JsonValue.class, Map.class, List.class)
                        .flatMap(contract -> Arrays.stream(contract.getMethods()))
                        .anyMatch(contract -> contract.getName().equals(method.getName())
                                && accepts(contract.getParameterTypes(), method.getParameterTypes()));
                if (method.getName().equals("delegateValue") || method.getName().equals("toString")
                        && method.getParameterCount() == 0 || method.getName().equals("equals")
                        || method.getName().equals("hashCode")) {
                    neutral = true;
                }
                assertEquals(!neutral, method.isAnnotationPresent(Deprecated.class), method.toString());
            }
        }
        assertTrue(JSONAware.class.getMethod("toJSONString").isAnnotationPresent(Deprecated.class));
        assertTrue(JSONStreamAware.class.getMethod("writeJSONString", Appendable.class).isAnnotationPresent(Deprecated.class));
    }

    public static final class PoisonBean {
        public int getValue() {
            throw new AssertionError("unsupported settings must fail before conversion");
        }
    }

    private static boolean accepts(Class<?>[] contract, Class<?>[] implementation) {
        if (contract.length != implementation.length) {
            return false;
        }
        for (int index = 0; index < contract.length; index++) {
            if (!contract[index].isAssignableFrom(implementation[index])) {
                return false;
            }
        }
        return true;
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        private int reads;
        private boolean closed;

        private TrackingInput(byte[] input) {
            super(input);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            reads++;
            return super.read(bytes, offset, length);
        }

        @Override
        public synchronized int read() {
            reads++;
            return super.read();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }


    @Test
    void entryViewsFollowJdkEqualityAndWriteThrough() {
        JSONObject object = JSON.parseObject("{\"n\":1}");
        Map.Entry<String, Object> entry = object.entrySet().iterator().next();
        Map.Entry<String, Object> standard = new java.util.AbstractMap.SimpleImmutableEntry<>("n", 1);
        assertEquals(standard, entry);
        assertEquals(entry, standard);
        assertEquals(standard.hashCode(), entry.hashCode());
        assertFalse(entry.equals("n"));
        assertFalse(entry.equals(new java.util.AbstractMap.SimpleImmutableEntry<>("other", 1)));
        assertFalse(entry.equals(new java.util.AbstractMap.SimpleImmutableEntry<>("n", 2)));
        assertEquals(1, entry.setValue(2));
        assertEquals(2, object.remove("n"));
        object.put("n", 3);
        var iterator = object.entrySet().iterator();
        iterator.next();
        iterator.remove();
        assertTrue(object.isEmpty());
        Map<String, Object> raw = new LinkedHashMap<>();
        object.put("child", raw);
        assertSame(raw, object.put("child", raw));
        assertSame(raw, object.entrySet().iterator().next().setValue(raw));
        assertSame(raw, object.remove("child"));
        JSONArray array = new JSONArray();
        array.add(raw);
        assertSame(raw, array.set(0, raw));
        assertSame(raw, array.remove(0));
    }
}
