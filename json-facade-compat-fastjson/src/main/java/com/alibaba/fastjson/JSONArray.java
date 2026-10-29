package com.alibaba.fastjson;

import java.lang.reflect.Type;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonArray;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.internal.JsonValues;

/**
 * 旧 JSONArray 的源码迁移容器；构造器共享 List，插入保留引用，修改返回旧值原引用。
 * 嵌套 get、迭代和 stream 按需提供兼容视图；不提供并发同步。
 * clone 浅复制，deepCopy 递归隔离；旧 getter 越界失败，node 仍保留缺失语义。
 * @deprecated 新代码使用 JsonArray、JsonMapper 和标准 List 方法。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class JSONArray extends JSON implements JsonArray, Cloneable {
    private JsonArray value;

    /** @deprecated 使用默认映射器；新代码使用 JsonMapper.createArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray() {
        this(Json.global());
    }

    /** @deprecated 固定映射器；新代码使用 JsonMapper.createArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray(JsonMapper mapper) {
        this(mapper, new ArrayList<>());
    }

    /** @deprecated 共享 List；隔离请用 deepCopy。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray(List<Object> source) {
        this(Json.global(), source);
    }

    /** @deprecated 共享 List 存储；新代码使用 JsonArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray(JsonMapper mapper, List<Object> source) {
        super(mapper);
        value = JsonValues.mutableArray(source, mapper);
    }

    /** @deprecated 指定初始容量；新代码使用标准 List 与 JsonArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray(int capacity) {
        this(Json.global(), new ArrayList<>(capacity));
    }

    @Override
    public JsonValue delegateValue() {
        return value;
    }

    @Override
    public Object get(int index) {
        return JSON.legacy(mapper(), value.get(index));
    }

    @Override
    public Object set(int index, Object item) {
        return value.set(index, item);
    }

    @Override
    public void add(int index, Object item) {
        value.add(index, item);
    }

    @Override
    public boolean add(Object item) {
        return value.add(item);
    }

    @Override
    public Object remove(int index) {
        return value.remove(index);
    }

    @Override
    public boolean remove(Object item) {
        return value.remove(item);
    }

    @Override
    public boolean contains(Object item) {
        return value.contains(item);
    }

    @Override
    public boolean containsAll(Collection<?> items) {
        return value.containsAll(items);
    }

    @Override
    public boolean isEmpty() {
        return value.isEmpty();
    }

    @Override
    public int indexOf(Object item) {
        return value.indexOf(item);
    }

    @Override
    public int lastIndexOf(Object item) {
        return value.lastIndexOf(item);
    }

    @Override
    public void clear() {
        value.clear();
    }

    @Override
    public boolean addAll(Collection<?> items) {
        return value.addAll(items);
    }

    @Override
    public boolean addAll(int index, Collection<?> items) {
        return value.addAll(index, items);
    }

    @Override
    public boolean removeAll(Collection<?> items) {
        return value.removeAll(items);
    }

    @Override
    public boolean retainAll(Collection<?> items) {
        return value.retainAll(items);
    }

    @Override
    public Object[] toArray() {
        return listView().toArray();
    }

    @Override
    public <T> T[] toArray(T[] target) {
        return listView().toArray(target);
    }

    @Override
    public Iterator<Object> iterator() {
        return listIterator();
    }

    @Override
    public ListIterator<Object> listIterator() {
        return listIterator(0);
    }

    @Override
    public List<Object> subList(int start, int end) {
        return new JSONArray(mapper(), value.subList(start, end));
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
    public JSONArray deepCopy() {
        return new JSONArray(mapper(), value.deepCopy());
    }

    @Override
    public ListIterator<Object> listIterator(int index) {
        ListIterator<Object> source = value.listIterator(index);
        return new ListIterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public Object next() {
                return JSON.legacy(mapper(), source.next());
            }

            @Override
            public boolean hasPrevious() {
                return source.hasPrevious();
            }

            @Override
            public Object previous() {
                return JSON.legacy(mapper(), source.previous());
            }

            @Override
            public int nextIndex() {
                return source.nextIndex();
            }

            @Override
            public int previousIndex() {
                return source.previousIndex();
            }

            @Override
            public void remove() {
                source.remove();
            }

            @Override
            public void set(Object item) {
                source.set(item);
            }

            @Override
            public void add(Object item) {
                source.add(item);
            }
        };
    }

    // 仅为 JDK 的数组导出算法临时建视图；不为每个容器常驻额外对象。
    private List<Object> listView() {
        return new AbstractList<>() {
            @Override
            public Object get(int index) {
                return JSONArray.this.get(index);
            }

            @Override
            public int size() {
                return value.size();
            }
        };
    }

    private JsonValue checkedNode(int index) {
        java.util.Objects.checkIndex(index, value.size());
        return value.node(index);
    }

    /** @deprecated null 返回 null，越界或形状不符失败；使用 JsonValue.getObject。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONObject getJSONObject(int index) {
        return JSON.object(mapper(), get(index));
    }

    /** @deprecated null 返回 null，越界失败；使用 JsonValue.getArray。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray getJSONArray(int index) {
        return JSON.array(mapper(), get(index));
    }

    /** @deprecated 使用 node(index).as(type)；旧入口越界失败。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T getObject(int index, Class<T> type) {
        return member(checkedNode(index), type);
    }

    /** @deprecated 使用 node(index).as(JsonType.of(type))。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T getObject(int index, Type type) {
        return member(checkedNode(index), type);
    }

    /** @deprecated 使用 as(JsonType.listOf(...))；由所属 Provider 绑定普通列表。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public <T> T toJavaList(Class<T> type) {
        return JSON.convert(mapper(), this, JsonType.listOf(JsonType.of(type)).type());
    }

    /** @deprecated 使用标准 List.add。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentAdd(Object item) {
        add(item);
        return this;
    }

    /** @deprecated 使用标准 List.add。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentAdd(int index, Object item) {
        add(index, item);
        return this;
    }

    /** @deprecated 使用标准 List.addAll。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentAddAll(Collection<?> items) {
        addAll(items);
        return this;
    }

    /** @deprecated 使用标准 List.addAll。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentAddAll(int index, Collection<?> items) {
        addAll(index, items);
        return this;
    }

    /** @deprecated 使用标准 List.set。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentSet(int index, Object item) {
        set(index, item);
        return this;
    }

    /** @deprecated 使用标准 List.remove。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentRemove(int index) {
        remove(index);
        return this;
    }

    /** @deprecated 使用标准 List.remove。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentRemove(Object item) {
        remove(item);
        return this;
    }

    /** @deprecated 使用标准 List.removeAll。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentRemoveAll(Collection<?> items) {
        removeAll(items);
        return this;
    }

    /** @deprecated 使用标准 List.retainAll。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentRetainAll(Collection<?> items) {
        retainAll(items);
        return this;
    }

    /** @deprecated 使用标准 List.clear。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray fluentClear() {
        clear();
        return this;
    }

    /** @deprecated 浅复制外层 List，嵌套共享；隔离请用 deepCopy。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONArray clone() {
        try {
            JSONArray copy = (JSONArray) super.clone();
            copy.value = JsonValues.mutableArray(new ArrayList<>(value), mapper());
            return copy;
        } catch (CloneNotSupportedException error) {
            throw new AssertionError(error);
        }
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public String getString(int index) {
        return JSON.scalar(checkedNode(index), JsonValues.STRING);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Integer getInteger(int index) {
        return JSON.scalar(checkedNode(index), JsonValues.INTEGER);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Long getLong(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Long.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Double getDouble(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Double.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Float getFloat(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Float.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Short getShort(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Short.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Byte getByte(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Byte.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Boolean getBoolean(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(Boolean.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.math.BigDecimal getBigDecimal(int index) {
        return JSON.scalar(checkedNode(index), JsonValues.BIG_DECIMAL);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.math.BigInteger getBigInteger(int index) {
        return JSON.scalar(checkedNode(index), JsonValues.BIG_INTEGER);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public byte[] getBytes(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(byte[].class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.util.Date getDate(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(java.util.Date.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.sql.Date getSqlDate(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(java.sql.Date.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public java.sql.Timestamp getTimestamp(int index) {
        return JSON.scalar(checkedNode(index), JsonType.of(java.sql.Timestamp.class));
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public int getIntValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? 0 : JSON.scalar(node, JsonValues.INT);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public long getLongValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? 0L : JSON.scalar(node, JsonValues.LONG);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public double getDoubleValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? 0D : JSON.scalar(node, JsonValues.DOUBLE);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public float getFloatValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? 0F : JSON.scalar(node, JsonValues.FLOAT);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public short getShortValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? (short) 0 : JSON.scalar(node, JsonValues.SHORT);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public byte getByteValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? (byte) 0 : JSON.scalar(node, JsonValues.BYTE);
    }

    /** @deprecated 使用 node(index).as；越界失败，null 返回 null 或基本类型默认值，转换由 Provider 决定。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public boolean getBooleanValue(int index) {
        JsonValue node = checkedNode(index);
        return node.isNull() ? false : JSON.scalar(node, JsonValues.BOOLEAN);
    }
}

