package org.jetlinks.json;

/**
 * JSON 数组视图，支持显式 null；工厂创建的数组在插入时递归复制容器。
 * {@link JsonMapper#wrap(java.util.List)} 返回只读借用视图；{@code deepCopy()} 可复制真实数组为独立可变存储。
 * 视图不提供同步，仅保留访问路径，不持有祖先数据。
 *
 * @since 1.0.0
 */
public interface JsonArray extends JsonValue {
    @Override
    JsonArray add(Object value);

    @Override
    JsonArray set(int index, Object value);

    @Override
    JsonArray deepCopy();
}
