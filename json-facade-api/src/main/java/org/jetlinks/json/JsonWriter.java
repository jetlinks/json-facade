package org.jetlinks.json;

/**
 * 手动拼装 JSON 对象和数组的写入器，适合边查数据边输出带包装字段的大结果。
 * <pre>{@code
 * var output = new java.io.ByteArrayOutputStream();
 * try (JsonWriter writer = mapper.writer(output)) {
 *     writer.beginObject();
 *     writer.name("total");
 *     writer.value(2);
 *     writer.name("items");
 *     writer.beginArray();
 *     for (int id : new int[] {1, 2}) {
 *         writer.value(java.util.Map.of("id", id)); // 实际可逐条写入查询得到的 DTO
 *     }
 *     writer.endArray();
 *     writer.endObject();
 * }
 * // 默认配置输出 {"total":2,"items":[{"id":1},{"id":2}]}，未收集完整结果 List。
 * }</pre>
 * 每次操作独立创建，不支持并发。begin/end 必须配对，name 后须写一个值；
 * 关闭不补齐未完成文档，也不关闭调用方输出流。适合阻塞式输出，不能任意暂停一个正在执行的序列化器。
 *
 * @since 1.0.0
 * @see JsonMapper#writer(java.io.OutputStream)
 */
public interface JsonWriter extends AutoCloseable {

    /**
     * 返回当前操作的类型化业务属性，缺失时为 null；嵌套 Adapter 继承相同上下文。
     * 属性仅传递数据，不自动改变读写行为；调用方不得修改共享值。
     * 例如 Adapter 内用 {@code Boolean debug = writer.attribute(INCLUDE_DEBUG);} 决定是否写出调试字段；
     * 完整示例见 {@link JsonAttribute}。
     *
     * @since 1.0.0
     */
    default <T> T attribute(JsonAttribute<T> key) {
        java.util.Objects.requireNonNull(key, "key");
        return null;
    }

    /**
     * 写入对象开始令牌。
     */
    void beginObject();

    /**
     * 结束当前对象。
     */
    void endObject();

    /**
     * 写入数组开始令牌。
     */
    void beginArray();

    /**
     * 结束当前数组。
     */
    void endArray();

    /**
     * 在对象内写入非空字段名，随后须写入字段值。
     */
    void name(String name);

    /**
     * 按当前映射器的注解和 Adapter 写入一个完整值，允许 Java null。
     * 例如 {@code writer.value(java.util.Map.of("id", "dev-1"));} 写出对象；
     * {@code writer.value("{\"id\":1}");} 写出字符串，不会把文本当作原始 JSON 插入。
     */
    void value(Object value);

    /**
     * 校验并原样写入数字文本，适合保留业务小数的精度和表示，例如 {@code writer.number("123.4500");}。
     * 不是写入任意 JSON 的入口；对象、NaN、Infinity 及非法数字文本会失败。
     */
    void number(String value);

    /**
     * 直接写入非空字符串令牌，不经过 Java 绑定适配器。
     */
    void string(String value);

    /**
     * 直接写入布尔令牌，不经过 Java 绑定适配器。
     */
    void bool(boolean value);

    /**
     * 直接写入 JSON null 令牌。
     */
    void nullValue();

    /**
     * 将已编码内容刷新到目标流；不补齐文档，不关闭目标流。
     */
    void flush();

    /**
     * 释放编码器，不补齐未闭合的容器，不关闭调用方输出流。
     */
    @Override
    void close();
}
