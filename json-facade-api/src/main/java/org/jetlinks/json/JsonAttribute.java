package org.jetlinks.json;

import java.util.Objects;

/**
 * 为自定义 Adapter 传递业务参数的键，例如是否输出调试字段、采用哪种脱敏模式。
 * 配置和读取必须使用同一个键实例；通常将键声明为 static final，不能用同名新键代替。
 * <pre>{@code
 * JsonAttribute<Boolean> includeDebug = JsonAttribute.of("include-debug", Boolean.class);
 * JsonMapper operation = mapper.withOptions(JsonOperationOptions.builder()
 *     .attribute(includeDebug, true).build());
 * var output = new java.io.ByteArrayOutputStream();
 * try (JsonWriter writer = operation.writer(output)) {
 *     writer.beginObject();
 *     if (Boolean.TRUE.equals(writer.attribute(includeDebug))) {
 *         writer.name("debug");
 *         writer.string("enabled");
 *     }
 *     writer.endObject();
 * }
 * // 自定义 Adapter 中也可以读取该属性；只有消费属性的代码才决定输出行为。
 * }</pre>
 * 属性也传给原生绑定上下文；框架只传参，不自动添加字段或实施脱敏。
 * 属性值不深复制，须稳定且线程安全。
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
