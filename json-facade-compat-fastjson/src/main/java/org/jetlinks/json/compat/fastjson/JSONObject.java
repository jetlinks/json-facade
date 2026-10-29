package org.jetlinks.json.compat.fastjson;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonObject;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;

/**
 * 旧 JSONObject 的源码迁移容器；修改方法返回旧值原引用，遵循中立集合契约。
 * Map 构造器共享传入存储，嵌套读取按需提供兼容视图；不提供并发同步。
 * @deprecated 新代码使用 JsonObject 与 JsonMapper；不兼容 fastjson 注解、Feature 和二进制包名。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class JSONObject extends AbstractMap<String, Object> implements JsonObject, JsonValues.DelegatingValue {
    private final JsonMapper mapper;
    private final JsonObject value;

    /** 使用共享默认映射器，适合已在启动阶段注册 Json.global 的应用。 */
    public JSONObject() {
        this(Json.global());
    }

    /** 显式提供映射器，避免全局发现并固定当前容器的绑定配置。 */
    public JSONObject(JsonMapper mapper) {
        this(mapper, new LinkedHashMap<>());
    }

    /** 保留旧构造器的共享 Map 语义。 */
    public JSONObject(Map<String, Object> source) {
        this(Json.global(), source);
    }

    /** 使用指定映射器共享 Map；隔离请调用 deepCopy。 */
    public JSONObject(JsonMapper mapper, Map<String, Object> source) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        value = JsonValues.mutableObject(source, mapper);
    }

    /** 内部绑定入口；直接委托已持有的中立节点，不创建遍历包装或快照。 */
    @Override
    public JsonValue delegateValue() {
        return value;
    }

    @Override
    public Object get(Object key) {
        return JSON.legacy(mapper, value.get(key));
    }

    @Override
    public Object put(String key, Object item) {
        return value.put(key, item);
    }

    @Override
    public Object remove(Object key) {
        return value.remove(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return value.containsKey(key);
    }

    @Override
    public int size() {
        return value.size();
    }

    @Override
    public void clear() {
        value.clear();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return value.size();
            }

            @Override
            public Iterator<Entry<String, Object>> iterator() {
                Iterator<Entry<String, Object>> source = value.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return source.hasNext();
                    }

                    @Override
                    public Entry<String, Object> next() {
                        Entry<String, Object> entry = source.next();
                        return new Entry<>() {
                            @Override
                            public String getKey() {
                                return entry.getKey();
                            }

                            @Override
                            public Object getValue() {
                                return JSON.legacy(mapper, entry.getValue());
                            }

                            @Override
                            public Object setValue(Object item) {
                                return entry.setValue(item);
                            }

                            @Override
                            public boolean equals(Object other) {
                                return other instanceof Map.Entry<?, ?> candidate
                                        && Objects.equals(getKey(), candidate.getKey())
                                        && Objects.equals(getValue(), candidate.getValue());
                            }

                            @Override
                            public int hashCode() {
                                return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
                            }
                        };
                    }

                    @Override
                    public void remove() {
                        source.remove();
                    }
                };
            }
        };
    }

    /** 缺失/null 返回 null；实际类型不符时报错，不隐式解析字符串。 */
    public JSONObject getJSONObject(String name) {
        return JSON.object(mapper, get(name));
    }

    /** 缺失/null 返回 null；实际类型不符时报错。 */
    public JSONArray getJSONArray(String name) {
        return JSON.array(mapper, get(name));
    }

    /** 使用所属 Provider 绑定 DTO。 */
    public <T> T getObject(String name, Class<T> type) {
        return value.node(name).as(type);
    }

    /** 使用所属 Provider 绑定当前对象。 */
    public <T> T toJavaObject(Class<T> type) {
        return value.as(type);
    }

    @Override
    public JSONObject deepCopy() {
        return new JSONObject(mapper, value.deepCopy());
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public boolean isMissing() {
        return false;
    }

    @Override
    public Object toJavaValue() {
        return value.toJavaValue();
    }

    @Override
    public JsonValue node(String name) {
        return value.node(name);
    }

    @Override
    public JsonValue node(int index) {
        return value.node(index);
    }

    @Override
    public <T> T as(JsonType<T> type) {
        return value.as(type);
    }

    /** 使用构造时的映射器输出，遵循其 null、日期及原生绑定配置。 */
    public String toJSONString() {
        return mapper.write(this);
    }

    @Override
    public String toString() {
        return toJSONString();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public String getString(String name) {
        return value.node(name).asString();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Integer getInteger(String name) {
        return value.node(name).asInteger();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Long getLong(String name) {
        return value.node(name).as(Long.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Double getDouble(String name) {
        return value.node(name).as(Double.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Float getFloat(String name) {
        return value.node(name).as(Float.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Short getShort(String name) {
        return value.node(name).as(Short.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Byte getByte(String name) {
        return value.node(name).as(Byte.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Boolean getBoolean(String name) {
        return value.node(name).as(Boolean.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public java.math.BigDecimal getBigDecimal(String name) {
        return value.node(name).asBigDecimal();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public java.math.BigInteger getBigInteger(String name) {
        return value.node(name).asBigInteger();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public int getIntValue(String name) {
        return value.node(name).asInt();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public long getLongValue(String name) {
        return value.node(name).asLong();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public double getDoubleValue(String name) {
        return value.node(name).asDouble();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public float getFloatValue(String name) {
        return value.node(name).asFloat();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public short getShortValue(String name) {
        return value.node(name).asShort();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public byte getByteValue(String name) {
        return value.node(name).asByte();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public boolean getBooleanValue(String name) {
        return value.node(name).asBoolean();
    }

}

