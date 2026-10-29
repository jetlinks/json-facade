package com.alibaba.fastjson;

import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
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
 * 旧 JSONObject 的源码迁移容器；构造器共享 Map，插入保留引用，修改返回旧值原引用。
 * 嵌套读取按需提供兼容视图；不提供并发同步，clone 浅复制，deepCopy 递归隔离。
 * 与原 fastjson 同名类互斥；DTO 内部旧容器、原生注解和完整二进制 API 不在兼容范围。
 * @deprecated 新代码使用 JsonObject、JsonMapper 和标准 Map 方法。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class JSONObject extends JSON implements JsonObject, Cloneable {
    private JsonObject value;

    /** @deprecated 使用默认映射器；新代码使用 JsonMapper.createObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject() {
        this(Json.global());
    }

    /** @deprecated 固定所属映射器；新代码使用 JsonMapper.createObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(JsonMapper mapper) {
        this(mapper, new LinkedHashMap<>());
    }

    /** @deprecated 共享 Map，隔离请用 deepCopy；新代码使用 JsonObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(Map<String, Object> source) {
        this(Json.global(), source);
    }

    /** @deprecated 共享 Map 存储；新代码使用 JsonObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(JsonMapper mapper, Map<String, Object> source) {
        super(mapper);
        value = JsonValues.mutableObject(source, mapper);
    }

    /** @deprecated 有序时保持插入顺序；新代码使用 JsonMapper.createObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(boolean ordered) {
        this(16, ordered);
    }

    /** @deprecated 指定初始容量；新代码使用标准 Map 与 JsonObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(int capacity) {
        this(capacity, false);
    }

    /** @deprecated 仅控制底层 Map 的容量和顺序，不改变 Provider 输出策略。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject(int capacity, boolean ordered) {
        this(Json.global(), ordered ? new LinkedHashMap<>(capacity) : new HashMap<>(capacity));
    }

    @Override
    public JsonValue delegateValue() {
        return value;
    }

    @Override
    public Object get(Object key) {
        return JSON.legacy(mapper(), value.get(key));
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
    public boolean containsValue(Object item) {
        return value.containsValue(item);
    }

    @Override
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public void clear() {
        value.clear();
    }

    @Override
    public void putAll(Map<? extends String, ?> source) {
        value.putAll(source);
    }

    @Override
    public Set<String> keySet() {
        return value.keySet();
    }

    @Override
    public Collection<Object> values() {
        return new AbstractMap<String, Object>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                return JSONObject.this.entrySet();
            }
        }.values();
    }

    @Override
    public boolean equals(Object other) {
        return value.equals(other);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public JSONObject deepCopy() {
        return new JSONObject(mapper(), value.deepCopy());
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
                                return JSON.legacy(mapper(), entry.getValue());
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


    /** @deprecated 缺失/null 返回 null，形状不符失败；使用 JsonValue.getObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject getJSONObject(String name) {
        return JSON.object(mapper(), get(name));
    }

    /** @deprecated 缺失/null 返回 null；使用 JsonValue.getArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray getJSONArray(String name) {
        return JSON.array(mapper(), get(name));
    }

    /** @deprecated 使用 node(name).as(type)；旧异常包装为 JSONException。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T getObject(String name, Class<T> type) {
        return member(value.node(name), type);
    }

    /** @deprecated 使用 node(name).as(JsonType.of(type))。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T getObject(String name, Type type) {
        return member(value.node(name), type);
    }

    /** @deprecated 使用 node(name).as(JsonType)。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T getObject(String name, TypeReference<T> type) {
        return member(value.node(name), type.getType());
    }

    /** @deprecated 使用标准 Map.put；此方法返回当前对象。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject fluentPut(String name, Object item) {
        put(name, item);
        return this;
    }

    /** @deprecated 使用标准 Map.putAll。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject fluentPutAll(Map<? extends String, ?> source) {
        putAll(source);
        return this;
    }

    /** @deprecated 使用标准 Map.remove。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject fluentRemove(Object name) {
        remove(name);
        return this;
    }

    /** @deprecated 使用标准 Map.clear。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject fluentClear() {
        clear();
        return this;
    }

    /** @deprecated 仅浅复制外层 Map，嵌套仍共享；隔离请用 deepCopy。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject clone() {
        try {
            JSONObject copy = (JSONObject) super.clone();
            copy.value = JsonValues.mutableObject(new LinkedHashMap<>(value), mapper());
            return copy;
        } catch (CloneNotSupportedException error) {
            throw new AssertionError(error);
        }
    }

    /** @deprecated 返回共享中立 Map 视图，不暴露原生引擎；新代码直接使用 JsonObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Map<String, Object> getInnerMap() {
        return value;
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public String getString(String name) {
        return JSON.scalar(value.node(name), JsonValues.STRING);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Integer getInteger(String name) {
        return JSON.scalar(value.node(name), JsonValues.INTEGER);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Long getLong(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Long.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Double getDouble(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Double.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Float getFloat(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Float.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Short getShort(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Short.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Byte getByte(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Byte.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Boolean getBoolean(String name) {
        return JSON.scalar(value.node(name), JsonType.of(Boolean.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.math.BigDecimal getBigDecimal(String name) {
        return JSON.scalar(value.node(name), JsonValues.BIG_DECIMAL);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.math.BigInteger getBigInteger(String name) {
        return JSON.scalar(value.node(name), JsonValues.BIG_INTEGER);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public byte[] getBytes(String name) {
        return JSON.scalar(value.node(name), JsonType.of(byte[].class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.util.Date getDate(String name) {
        return JSON.scalar(value.node(name), JsonType.of(java.util.Date.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.sql.Date getSqlDate(String name) {
        return JSON.scalar(value.node(name), JsonType.of(java.sql.Date.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.sql.Timestamp getTimestamp(String name) {
        return JSON.scalar(value.node(name), JsonType.of(java.sql.Timestamp.class));
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public int getIntValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? 0 : JSON.scalar(node, JsonValues.INT);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public long getLongValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? 0L : JSON.scalar(node, JsonValues.LONG);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public double getDoubleValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? 0D : JSON.scalar(node, JsonValues.DOUBLE);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public float getFloatValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? 0F : JSON.scalar(node, JsonValues.FLOAT);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public short getShortValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? (short) 0 : JSON.scalar(node, JsonValues.SHORT);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public byte getByteValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? (byte) 0 : JSON.scalar(node, JsonValues.BYTE);
    }

    /** @deprecated 使用 node(name).as；转换由 Provider 决定，缺失/null 返回 null 或基本类型默认值。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public boolean getBooleanValue(String name) {
        JsonValue node = value.node(name);
        return node.isMissing() || node.isNull() ? false : JSON.scalar(node, JsonValues.BOOLEAN);
    }
}

