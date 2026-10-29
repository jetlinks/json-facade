package org.jetlinks.json;

import java.util.Map;

/**
 * String 键的 JSON 对象；get 返回原始值，put/remove 返回旧值，写入保留引用。
 * node 提供缺失/null 区分和路径诊断；wrap 创建嵌套只读借用，deepCopy 返回独立可变树。
 * 不提供并发同步；序列化使用所属 JsonMapper。
 *
 * @since 1.0.0
 * @see JsonMapper
 */
public interface JsonObject extends JsonValue, Map<String, Object> {
    @Override
    JsonObject deepCopy();
}
