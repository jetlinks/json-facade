package org.jetlinks.json;

/**
 * 单次操作独占的同步令牌游标，不支持并发使用；Provider 负责原生解析和绑定。
 * 关闭仅释放解析器，不关闭调用方输入流；适用原生令牌和深度限制，不适用增量单值字节预算。
 *
 * @since 1.0.0
 * @see JsonMapper#reader(java.io.InputStream)
 */
public interface JsonReader extends AutoCloseable {

    /**
     * 前进一个令牌；真正 EOF 返回 null，解析错误抛出 {@link JsonException}。
     */
    JsonToken next();

    /**
     * 返回当前令牌；首次前进前及绑定完整值后返回 null。
     */
    JsonToken current();

    /**
     * 返回当前字段名或标量文本；应在适用的令牌位置调用。
     */
    String text();

    /**
     * 从当前值的首令牌起绑定完整值；随后 {@code next()} 前进到其后继。
     *
     * @param type 非空目标类型
     * @param <T> 目标 Java 类型
     * @return 绑定结果，可以为 Java null
     */
    <T> T readValue(JsonType<T> type);

    /**
     * 按类绑定当前完整值，消耗范围同泛型重载。
     *
     * @see #readValue(JsonType)
     */
    default <T> T readValue(Class<T> type) {
        return readValue(JsonType.of(type));
    }

    /**
     * 跳过当前标量或整个容器，不物化其内容。
     */
    void skipValue();

    /**
     * 释放解析器状态，不关闭调用方输入流。
     */
    @Override
    void close();
}
