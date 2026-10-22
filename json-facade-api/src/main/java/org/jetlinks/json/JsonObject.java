package org.jetlinks.json;

/**
 * String 键的 JSON 对象视图；工厂创建的对象在插入时递归复制容器及嵌套节点。
 * {@link JsonMapper#wrap(java.util.Map)} 返回只读借用视图；{@code deepCopy()} 可复制真实对象为独立可变存储。
 * 视图不提供同步，仅保留访问路径，不持有祖先数据。
 *
 * @since 1.0.0
 */
public interface JsonObject extends JsonValue {
    @Override
    JsonObject put(String name, Object value);

    @Override
    JsonObject remove(String name);

    @Override
    JsonObject deepCopy();
}
