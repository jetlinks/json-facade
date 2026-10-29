package com.alibaba.fastjson;

import java.util.List;
import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonType;

/**
 * fastjson 1.x 原包名的源码迁移入口，全部绑定交给 JsonMapper；须移除原 fastjson 依赖。
 * 默认入口使用 Json.global；带 mapper 的重载适合多配置应用。
 * @deprecated 新代码直接使用 JsonMapper；只承接常用源码调用，不实现旧解析器或原生配置。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public final class JSON {
    private JSON() { }

    /** 解析对象，JSON null 返回 null。 */
    public static JSONObject parseObject(String input) {
        return parseObject(Json.global(), input);
    }

    /** 使用指定映射器解析对象，嵌套容器按访问转换为兼容视图。 */
    public static JSONObject parseObject(JsonMapper mapper, String input) {
        return object(mapper, mapper.read(input, JsonType.mapOf(JsonType.of(String.class), JsonType.of(Object.class))));
    }

    /** 解析数组，JSON null 返回 null。 */
    public static JSONArray parseArray(String input) {
        return parseArray(Json.global(), input);
    }

    /** 使用指定映射器解析数组。 */
    public static JSONArray parseArray(JsonMapper mapper, String input) {
        return array(mapper, mapper.read(input, JsonType.listOf(JsonType.of(Object.class))));
    }

    /** 解析任意 JSON 值，容器返回兼容类型，标量保持原始值。 */
    public static Object parse(String input) {
        return parse(Json.global(), input);
    }

    /** 使用指定映射器解析完整单根值。 */
    public static Object parse(JsonMapper mapper, String input) {
        return legacy(mapper, mapper.read(input, Object.class));
    }

    /** 按声明类型读取 DTO；注解和多态遵循所选 Provider。 */
    public static <T> T parseObject(String input, Class<T> type) {
        return parseObject(Json.global(), input, type);
    }

    /** 显式映射器的 DTO 入口；兼容容器仅在此根入口适配，不注册引擎专有绑定。 */
    public static <T> T parseObject(JsonMapper mapper, String input, Class<T> type) {
        if (type == JSONObject.class) {
            return type.cast(parseObject(mapper, input));
        }
        if (type == JSONArray.class) {
            return type.cast(parseArray(mapper, input));
        }
        return mapper.read(input, type);
    }

    /** 泛型入口直接使用中立 JsonType，不保留 fastjson TypeReference 耦合。 */
    public static <T> T parseObject(String input, JsonType<T> type) {
        return Json.global().read(input, type);
    }

    /** 读取指定元素类型的普通列表。 */
    public static <T> List<T> parseArray(String input, Class<T> type) {
        return Json.global().read(input, JsonType.listOf(JsonType.of(type)));
    }

    /** 按默认映射器输出完整 JSON。 */
    public static String toJSONString(Object value) {
        return Json.global().write(value);
    }

    /** 按默认映射器输出 UTF-8。 */
    public static byte[] toJSONBytes(Object value) {
        return Json.global().writeBytes(value);
    }

    /** 将 POJO 交给 Provider 转为普通 JSON 数据，再按需提供兼容容器。 */
    public static Object toJSON(Object value) {
        JsonMapper mapper = Json.global();
        return legacy(mapper, mapper.convert(value, Object.class));
    }

    /** 通过默认映射器转换 DTO。 */
    public static <T> T toJavaObject(Object value, Class<T> type) {
        return Json.global().convert(value, type);
    }

    static Object legacy(JsonMapper mapper, Object value) {
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return new JSONObject(mapper, stringMap(map));
        }
        if (value instanceof List<?> list) {
            return new JSONArray(mapper, objectList(list));
        }
        return value;
    }

    static JSONObject object(JsonMapper mapper, Object value) {
        Object result = legacy(mapper, value);
        if (result == null || result instanceof JSONObject) {
            return (JSONObject) result;
        }
        throw new JsonException(JsonException.Kind.BINDING, "Expected a JSON object");
    }

    static JSONArray array(JsonMapper mapper, Object value) {
        Object result = legacy(mapper, value);
        if (result == null || result instanceof JSONArray) {
            return (JSONArray) result;
        }
        throw new JsonException(JsonException.Kind.BINDING, "Expected a JSON array");
    }

    // JSON 解析产物和中立容器已约定键和值类型；仅恢复泛型，不扫描或复制图。
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> objectList(List<?> value) {
        return (List<Object>) value;
    }
}

