package org.jetlinks.json;

/**
 * 单次操作独占的同步令牌写入器，不支持并发使用；Provider 持有编码器状态。
 * 按顺序配对容器开始、结束与字段名；关闭不补齐未完成文档，也不关闭调用方输出流。
 *
 * @since 1.0.0
 * @see JsonMapper#writer(java.io.OutputStream)
 */
public interface JsonWriter extends AutoCloseable {

    /**
     * 返回当前操作的类型化业务属性，缺失时为 null；嵌套 Adapter 继承相同上下文。
     * 属性仅传递数据，不自动改变读写行为；调用方不得修改共享值。
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
     * 按当前映射器绑定配置写入一个完整值，允许 Java null。
     */
    void value(Object value);

    /**
     * 校验并原样写入数字文本，避免经 double 中转；不接受任意原始 JSON 片段。
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
