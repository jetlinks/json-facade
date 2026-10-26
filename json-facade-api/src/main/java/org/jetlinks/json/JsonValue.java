package org.jetlinks.json;

import org.jetlinks.json.internal.JsonArrayIndex;
import org.jetlinks.json.internal.JsonValues;

/**
 * 统一访问 JSON 对象、数组和标量，适合字段结构不固定的配置或接口结果。
 * <pre>{@code
 * JsonValue value = mapper.readTree("{\"items\":[{\"id\":1}],\"remark\":null}");
 * int id = value.get("items").get(0).get("id").asInt(); // 1
 * boolean explicitNull = value.get("remark").isNull(); // true：存在且值为 null
 * boolean missing = value.get("unknown").isMissing();  // true：字段不存在
 * value.asObject().put("online", true);
 * String json = mapper.write(value);
 * }</pre>
 * 区分显式 {@code NULL} 与路径缺失 {@code MISSING}；找不到字段仍可继续 get，错误位置保留在节点上。
 * <p>结构工厂创建可变容器，借用和缺失视图拒绝写入；所有视图均不提供并发同步。
 * {@code toJavaValue()} 和 {@code deepCopy()} 隔离嵌套容器；POJO 绑定须使用所属映射器。
 *
 * @since 1.0.0
 * @see JsonMapper#readTree(String)
 * @see JsonMapper#wrap(java.util.Map)
 */
public interface JsonValue {

    /**
     * 显式 JSON null；不是缺失值，不携带映射器配置。
     */
    JsonValue NULL = JsonValues.nullValue();

    /**
     * 路径缺失标记；不是显式 null，不携带映射器配置。
     */
    JsonValue MISSING = JsonValues.missingValue();

    /**
     * 递归复制 JSON 兼容的 Java 容器；传入已有 {@code JsonValue} 时直接返回原节点。
     * 数字仅接受精确的 JDK 包装数字、BigInteger 和 BigDecimal 类型。
     * 可变或自定义 Number、POJO 请使用 {@link JsonMapper#toTree(Object)}；容器须无环。
     * 复制使用 {@link JsonOptions#DEFAULT} 的 maxDepth；超深或循环容器在上限处以 LIMIT 失败。
     */
    static JsonValue of(Object value) {
        return JsonValues.of(value);
    }

    /**
     * 创建无绑定配置的可变空对象；需要类型转换时使用 {@link JsonMapper#createObject()}。
     */
    static JsonObject object() {
        return JsonValues.object();
    }

    /**
     * 创建无绑定配置的可变空数组；需要类型转换时使用 {@link JsonMapper#createArray()}。
     */
    static JsonArray array() {
        return JsonValues.array();
    }

    /**
     * 是否为显式 JSON null。
     */
    boolean isNull();

    /**
     * 是否为不存在的字段或索引。
     */
    boolean isMissing();

    /**
     * 导出普通 Java 标量或独立 Map/List，适合交给仅接受 JDK 容器的代码。
     * {@code Object data = mapper.readTree("{\"id\":1}").toJavaValue();}
     * 嵌套容器会复制，修改导出容器不修改树；显式 null 返回 Java null，缺失值导出失败。
     * 复制使用所属映射器的 maxDepth，无映射器时使用默认预算。
     */
    Object toJavaValue();

    /**
     * 读取对象字段；字段不存在或当前值不是对象时返回保留访问路径的缺失视图。
     */
    JsonValue get(String name);

    /**
     * 读取数组元素；越界或当前值不是数组时返回保留访问路径的缺失视图。
     */
    JsonValue get(int index);

    /**
     * 返回对象字段数或数组元素数；标量、null 和缺失视图返回 0。
     */
    int size();

    /**
     * 递归复制并保留所属映射器，位置重置为根；真实容器可写，缺失仍为缺失。
     */
    JsonValue deepCopy();

    /**
     * 使用所属映射器的 Provider 转换；缺失或 null 返回 Java null，真实值的转换异常向外传播。
     * 无绑定配置的结构节点须显式使用 {@link JsonMapper#convert(Object, JsonType)}。
     * 自定义存储实现须覆写此绑定入口。
     * 例如从动态文档取出局部对象，再交给业务 DTO：
     * <pre>{@code
     * record Device(String id) {}
     * JsonValue root = mapper.readTree("{\"device\":{\"id\":\"dev-1\"}}");
     * Device device = root.get("device").as(Device.class);
     * }</pre>
     */
    default <T> T as(JsonType<T> type) {
        return JsonValues.convert(this, type);
    }

    /**
     * 按类转换，缺失或 null 返回 Java null；其他语义同 {@link #as(JsonType)}。
     */
    default <T> T as(Class<T> type) {
        return as(JsonType.of(type));
    }

    /**
     * 返回相对于视图根的绝对访问位置；深拷贝后的节点从根重新计位。
     * 自定义存储须同时覆写 {@code pointer()} 和 {@code displayPath()}，以保留缺失视图的诊断位置。
     */
    default JsonPointer pointer() {
        return JsonPointer.of("");
    }

    /**
     * 返回便于阅读的诊断路径，根为 {@code $}；机器定位请用 {@link #pointer()}。
     */
    default String displayPath() {
        return "$";
    }

    /**
     * 返回非 null 对象视图；缺失或 null 可继续链式读取，写入失败且不自动创建数据。
     * 存在但不是对象时抛出类型错误。
     */
    default JsonObject getObject(String name) {
        return JsonValues.objectView(JsonValues.member(this, name));
    }

    /**
     * 读取数组中的对象视图；缺失或 null 可链式读取，类型不符时失败。
     */
    default JsonObject getObject(int index) {
        return JsonValues.objectView(JsonValues.element(this, index));
    }

    /**
     * 读取字段的数组视图；缺失或 null 可链式读取，类型不符时失败。
     */
    default JsonArray getArray(String name) {
        return JsonValues.arrayView(JsonValues.member(this, name));
    }

    /**
     * 读取数组中的数组视图；缺失或 null 可链式读取，类型不符时失败。
     */
    default JsonArray getArray(int index) {
        return JsonValues.arrayView(JsonValues.element(this, index));
    }

    /**
     * 要求路径存在，允许显式 JSON null；缺失时抛出包含位置的 {@link JsonException}。
     */
    default JsonValue requirePresent() {
        return JsonValues.require(this, !isMissing(), org.jetlinks.json.JsonFailure.Reason.MISSING, null);
    }

    /**
     * 要求路径存在；失败时调用非空异常工厂，生成的异常原样抛出。
     */
    default JsonValue requirePresent(
            java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, !isMissing(), JsonFailure.Reason.MISSING, errors);
    }

    /**
     * 要求值存在且非 null；允许空字符串和空容器，失败时抛出包含位置的 {@link JsonException}。
     */
    default JsonValue requireNonNull() {
        return JsonValues.require(this, !isMissing() && !isNull(),
                isMissing() ? JsonFailure.Reason.MISSING : JsonFailure.Reason.NULL, null);
    }

    /**
     * 要求值存在且非 null；失败时调用非空异常工厂，生成的异常原样抛出。
     */
    default JsonValue requireNonNull(
            java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, !isMissing() && !isNull(),
                isMissing() ? JsonFailure.Reason.MISSING : JsonFailure.Reason.NULL, errors);
    }

    /**
     * 仅求值一次，条件不满足时抛出断言错误；谓词自身抛出的异常原样传播。
     */
    default JsonValue require(java.util.function.Predicate<? super JsonValue> predicate) {
        return JsonValues.require(this, predicate.test(this), JsonFailure.Reason.ASSERTION, null);
    }

    /**
     * 条件不满足时调用非空异常工厂；谓词和工厂异常原样传播。
     */
    default JsonValue require(java.util.function.Predicate<? super JsonValue> predicate,
                             java.util.function.Function<? super JsonFailure, ? extends RuntimeException> errors) {
        java.util.Objects.requireNonNull(errors, "errors");
        return JsonValues.require(this, predicate.test(this), JsonFailure.Reason.ASSERTION, errors);
    }

    /**
     * 缺失或 null 返回 0；真实值通过所属 Provider 转为 int，保留其类型转换规则。
     */
    default int asInt() {
        return asInt(0);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default int asInt(int defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.INT);
    }

    /**
     * 缺失或 null 返回 0L；真实值通过所属 Provider 转为 long。
     */
    default long asLong() {
        return asLong(0L);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default long asLong(long defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.LONG);
    }

    /**
     * 缺失或 null 返回 0D；真实值通过所属 Provider 转为 double。
     */
    default double asDouble() {
        return asDouble(0D);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default double asDouble(double defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.DOUBLE);
    }

    /**
     * 缺失或 null 返回 0F；真实值通过所属 Provider 转为 float。
     */
    default float asFloat() {
        return asFloat(0F);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default float asFloat(float defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.FLOAT);
    }

    /**
     * 缺失或 null 返回 0；真实值通过所属 Provider 转为 short。
     */
    default short asShort() {
        return asShort((short) 0);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default short asShort(short defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.SHORT);
    }

    /**
     * 缺失或 null 返回 0；真实值通过所属 Provider 转为 byte。
     */
    default byte asByte() {
        return asByte((byte) 0);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default byte asByte(byte defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BYTE);
    }

    /**
     * 缺失或 null 返回 false；真实值通过所属 Provider 转为 boolean。
     */
    default boolean asBoolean() {
        return asBoolean(false);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default boolean asBoolean(boolean defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BOOLEAN);
    }

    /**
     * 缺失或 null 返回 Java null；真实值通过所属 Provider 转为字符串。
     */
    default String asString() {
        return asString(null);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default String asString(String defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.STRING);
    }

    /**
     * 缺失或 null 返回 Java null；真实值通过所属 Provider 转为 Integer。
     */
    default Integer asInteger() {
        return asInteger(null);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default Integer asInteger(Integer defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.INTEGER);
    }

    /**
     * 缺失或 null 返回 Java null；真实值通过所属 Provider 转为 BigDecimal。
     */
    default java.math.BigDecimal asBigDecimal() {
        return asBigDecimal(null);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default java.math.BigDecimal asBigDecimal(java.math.BigDecimal defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BIG_DECIMAL);
    }

    /**
     * 缺失或 null 返回 Java null；真实值通过所属 Provider 转为 BigInteger。
     */
    default java.math.BigInteger asBigInteger() {
        return asBigInteger(null);
    }

    /**
     * 缺失或 null 返回给定默认值；真实值的 Provider 转换失败仍向外传播。
     */
    default java.math.BigInteger asBigInteger(java.math.BigInteger defaultValue) {
        return isMissing() || isNull() ? defaultValue : as(JsonValues.BIG_INTEGER);
    }

    /**
     * 返回当前对象视图；缺失、null 或非对象值均抛出类型错误，不执行数据转换。
     */
    default JsonObject asObject() {
        if (!isMissing() && !isNull() && this instanceof JsonObject object) {
            return object;
        }
        throw JsonValues.shapeFailure(this, "object");
    }

    /**
     * 返回当前数组视图；缺失、null 或非数组值均抛出类型错误，不执行数据转换。
     */
    default JsonArray asArray() {
        if (!isMissing() && !isNull() && this instanceof JsonArray array) {
            return array;
        }
        throw JsonValues.shapeFailure(this, "array");
    }

    /**
     * 按 RFC 6901 Pointer 相对当前节点定位；空 Pointer 返回自身，未命中返回保留路径的缺失视图。
     */
    default JsonValue at(JsonPointer pointer) {
        JsonValue current = this;
        for (String segment : pointer.segments()) {
            if (current instanceof JsonArray) {
                long index = JsonArrayIndex.parse(segment);
                // 树索引使用 int；无效或超范围的段保留原始访问路径。
                current = index >= 0 && index <= Integer.MAX_VALUE
                        ? current.get((int) index) : current.get(segment);
            } else {
                current = current.get(segment);
            }
        }
        return current;
    }

    /**
     * 向可变对象写入字段并递归复制值，返回自身；借用、缺失或类型不符的视图写入失败。
     */
    default JsonObject put(String name, Object value) {
        return asObject().put(name, value);
    }

    /**
     * 从可变对象移除字段并返回自身；字段不存在时无副作用，借用或缺失视图写入失败。
     */
    default JsonObject remove(String name) {
        return asObject().remove(name);
    }

    /**
     * 向可变数组追加值的递归副本并返回自身；借用、缺失或类型不符的视图写入失败。
     */
    default JsonArray add(Object value) {
        return asArray().add(value);
    }

    /**
     * 替换已有数组元素并递归复制值，返回自身；越界失败，不扩展数组，借用或缺失视图写入失败。
     */
    default JsonArray set(int index, Object value) {
        return asArray().set(index, value);
    }
}
