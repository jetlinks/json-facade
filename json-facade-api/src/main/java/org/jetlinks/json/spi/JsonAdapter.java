package org.jetlinks.json.spi;

import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonWriter;

/**
 * 针对一个精确 Java 类型的绑定扩展，实例须线程安全，可跨操作复用。
 * 在映射器调用线程同步执行，无隐式调度、重试或 I/O；null 由映射器策略处理，不调用适配器。
 *
 * @param <T> 支持的类型
 * @since 1.0.0
 * @see JsonModuleContext
 */
public interface JsonAdapter<T> {

    /**
     * 写入恰好一个 JSON 值；嵌套值可使用同一已配置写入器。
     * 不得把同一个值递归委托回当前适配器。
     *
     * @param value 已注册类型的非空值
     * @param writer 借用的当前写入器，不得关闭或持有
     * @throws org.jetlinks.json.JsonException 无法编码时抛出，不自动重试
     * @since 1.0.0
     */
    void write(T value, JsonWriter writer);

    /**
     * 从首令牌起恰好消费一个完整值，绑定错误向调用方传播。
     *
     * @param reader 已定位值首令牌的借用游标，不得关闭或持有
     * @return 绑定结果，可为 null；响应式 null 策略由流适配器处理
     * @throws org.jetlinks.json.JsonException 绑定失败时抛出，不自动重试
     * @since 1.0.0
     */
    T read(JsonReader reader);
}
