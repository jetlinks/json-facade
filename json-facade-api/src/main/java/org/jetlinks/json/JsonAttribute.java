package org.jetlinks.json;

import java.util.Objects;

/**
 * 操作上下文的类型化属性键；按对象身份区分，请复用同一个键实例。
 * 属性仅传递数据，行为由自定义 Adapter 或原生处理器实现；值须稳定且线程安全。
 *
 * @param <T> 属性值类型
 * @since 1.0.0
 * @see JsonOperationOptions.Builder#attribute(JsonAttribute, Object)
 */
public final class JsonAttribute<T> {
    private final String name;
    private final Class<T> type;

    private JsonAttribute(String name, Class<T> type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("Attribute type must be a reference type");
        }
    }

    /** 创建身份独立的键；name 用于诊断，同名键不互通。 */
    public static <T> JsonAttribute<T> of(String name, Class<T> type) {
        return new JsonAttribute<>(name, type);
    }

    /** 检查并返回原生上下文中的值；缺失时返回 null，类型错误时立即失败。 */
    public T cast(Object value) {
        return type.cast(value);
    }

    @Override
    public String toString() {
        return name;
    }
}
