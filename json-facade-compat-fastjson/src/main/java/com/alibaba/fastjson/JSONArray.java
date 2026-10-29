package com.alibaba.fastjson;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;

/**
 * 旧 JSONArray 的源码迁移容器，保留插入引用；修改方法返回旧值原引用。
 * List 构造器共享存储；嵌套 get、迭代和 stream 返回同一数据上的兼容视图。
 * 不提供并发同步。
 * 与原 fastjson 同名类互斥，只兼容已提供的常用源码 API。
 * @deprecated 新代码使用 JsonArray 与 JsonMapper；不兼容 fastjson Feature 或完整二进制 API。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class JSONArray extends AbstractList<Object> implements JsonArray, JsonValues.DelegatingValue {
    private final JsonMapper mapper;
    private final JsonArray value;

    /** 使用共享默认映射器。 */
    public JSONArray() {
        this(Json.global());
    }

    /** 使用指定映射器创建独立空数组。 */
    public JSONArray(JsonMapper mapper) {
        this(mapper, new ArrayList<>());
    }

    /** 共享旧 List；隔离请用 deepCopy。 */
    public JSONArray(List<Object> source) {
        this(Json.global(), source);
    }

    /** 使用指定映射器共享 List 存储。 */
    public JSONArray(JsonMapper mapper, List<Object> source) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        value = JsonValues.mutableArray(source, mapper);
    }

    /** 内部绑定入口；直接委托已持有的中立节点，不创建遍历包装或快照。 */
    @Override
    public JsonValue delegateValue() {
        return value;
    }

    @Override
    public Object get(int index) {
        return JSON.legacy(mapper, value.get(index));
    }

    @Override
    public Object set(int index, Object item) {
        return value.set(index, item);
    }

    @Override
    public void add(int index, Object item) {
        value.add(index, item);
        modCount++;
    }

    @Override
    public Object remove(int index) {
        Object removed = value.remove(index);
        modCount++;
        return removed;
    }

    @Override
    public int size() {
        return value.size();
    }

    /** 按索引取得兼容对象；null 返回 null，形状不符时失败。 */
    public JSONObject getJSONObject(int index) {
        return JSON.object(mapper, get(index));
    }

    /** 按索引取得兼容数组。 */
    public JSONArray getJSONArray(int index) {
        return JSON.array(mapper, get(index));
    }

    /** 使用所属 Provider 绑定元素。 */
    public <T> T getObject(int index, Class<T> type) {
        return value.node(index).as(type);
    }

    /** 使用所属 Provider 绑定为普通列表。 */
    public <T> List<T> toJavaList(Class<T> type) {
        return value.as(JsonType.listOf(JsonType.of(type)));
    }

    @Override
    public JSONArray deepCopy() {
        return new JSONArray(mapper, value.deepCopy());
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
    public String getString(int index) {
        return value.node(index).asString();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Integer getInteger(int index) {
        return value.node(index).asInteger();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Long getLong(int index) {
        return value.node(index).as(Long.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Double getDouble(int index) {
        return value.node(index).as(Double.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Float getFloat(int index) {
        return value.node(index).as(Float.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Short getShort(int index) {
        return value.node(index).as(Short.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Byte getByte(int index) {
        return value.node(index).as(Byte.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public Boolean getBoolean(int index) {
        return value.node(index).as(Boolean.class);
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public java.math.BigDecimal getBigDecimal(int index) {
        return value.node(index).asBigDecimal();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public java.math.BigInteger getBigInteger(int index) {
        return value.node(index).asBigInteger();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public int getIntValue(int index) {
        return value.node(index).asInt();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public long getLongValue(int index) {
        return value.node(index).asLong();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public double getDoubleValue(int index) {
        return value.node(index).asDouble();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public float getFloatValue(int index) {
        return value.node(index).asFloat();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public short getShortValue(int index) {
        return value.node(index).asShort();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public byte getByteValue(int index) {
        return value.node(index).asByte();
    }

    /** 读取并转换值；缺失/null 的引用结果为 null，基本类型结果为零或 false。 */
    public boolean getBooleanValue(int index) {
        return value.node(index).asBoolean();
    }

}

