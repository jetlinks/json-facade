package org.jetlinks.json.compat;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
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
        Json.registerGlobal(Json.builder().provider("jackson2").build());
    }

    private JsonMapper mapper(String provider) {
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
        assertThrows(org.jetlinks.json.JsonException.class, () -> object.getJSONArray("n"));
        assertThrows(org.jetlinks.json.JsonException.class, () -> array.getJSONObject(0));
        assertEquals(Map.of(), array.getJSONObject(3));
        assertEquals(List.of(), array.getJSONArray(4));
        assertEquals(Arrays.asList("12", true, null, Map.of(), List.of()), array);
        assertEquals(array, JSON.parse(array.toString()));
        assertNull(object.getInteger("nil"));
        assertEquals(0, object.getIntValue("missing"));
    }

    public record Item(int value) { }

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
