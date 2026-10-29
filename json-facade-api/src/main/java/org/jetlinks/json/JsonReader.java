package org.jetlinks.json;

/**
 * 手动前进令牌、读取选中字段或跳过无关内容的同步游标，适合只关心大对象中的少数字段。
 * 例如只取 id，跳过 debug 对象，不把 debug 转为 Map：
 * <pre>{@code
 * byte[] json = "{\"id\":\"dev-1\",\"debug\":{\"raw\":[1,2]}}"
 *     .getBytes(java.nio.charset.StandardCharsets.UTF_8);
 * try (var input = new java.io.ByteArrayInputStream(json);
 *      JsonReader reader = mapper.reader(input)) {
 *     if (reader.next() != JsonToken.START_OBJECT) {
 *         throw new IllegalArgumentException("Expected object");
 *     }
 *     String id = null;
 *     while (reader.next() == JsonToken.FIELD_NAME) {
 *         String name = reader.text();
 *         reader.next(); // 从字段名推进到字段值
 *         if ("id".equals(name)) {
 *             id = reader.readValue(String.class);
 *         } else {
 *             reader.skipValue();
 *         }
 *     }
 *     if (reader.current() != JsonToken.END_OBJECT || reader.next() != null) {
 *         throw new IllegalArgumentException("Expected object end and EOF");
 *     }
 *     // id 为 dev-1；next() 已读到 EOF，整篇输入才算验证完成。
 * }
 * }</pre>
 * 每次操作独立创建，不支持并发；读取可能等待调用方输入流，因此不能直接当作非阻塞网络解析器。
 * 关闭仅释放解析器，不关闭输入流。适用原生令牌和深度限制，不适用增量会话的单值字节预算。
 * 仅需按数组元素读取时优先用 {@link JsonMapper#iterateArray(java.io.InputStream, Class)}。
 *
 * @since 1.0.0
 * @see JsonMapper#reader(java.io.InputStream)
 */
public interface JsonReader extends AutoCloseable {

    /**
     * 返回当前操作的类型化业务属性，缺失时为 null；嵌套 Adapter 继承相同上下文。
     * 属性仅传递数据，不自动改变读写行为；调用方不得修改共享值。
     * 例如 Adapter 内用 {@code String mode = reader.attribute(MASK_MODE);} 决定如何解码业务值，
     * MASK_MODE 须与 {@link JsonOperationOptions.Builder#attribute} 使用同一键实例。
     *
     * @since 1.0.0
     */
    default <T> T attribute(JsonAttribute<T> key) {
        java.util.Objects.requireNonNull(key, "key");
        return null;
    }

    /**
     * 前进一个令牌；真正 EOF 返回 null，解析错误抛出 {@link JsonException}。
     */
    JsonToken next();

    /**
     * 返回当前令牌；首次前进前及绑定完整值后返回 null。
     */
    JsonToken current();

    /**
     * 返回当前字段名或标量文本，例如 FIELD_NAME 时得到 {@code "id"}，NUMBER 时得到 {@code "12.5"}。
     * 数字文本适合需要原始精度的业务解析；对象/数组开始结束位置不应用此方法取值。
     */
    String text();

    /**
     * 从当前值的首令牌起绑定完整值；随后 {@code next()} 前进到其后继。
     * 例如 next 返回 START_OBJECT 后，可用 {@code Device row = reader.readValue(Device.class);} 读取该对象。
     * 当前值仍会完整物化；只需忽略内容时用 {@link #skipValue()}。
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
     * 跳过当前标量或整个容器，不创建对应 Map/List/DTO；适合忽略不需要的 debug、history 等字段。
     * 须先从字段名推进到值；跳过后调用 next 前进到后继，不会自动读取下一个字段。
     */
    void skipValue();

    /**
     * 释放解析器状态，不关闭调用方输入流。
     */
    @Override
    void close();
}
