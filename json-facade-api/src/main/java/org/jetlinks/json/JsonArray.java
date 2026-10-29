package org.jetlinks.json;

import java.util.List;

/**
 * JSON 数组；get 返回原始值且越界失败，支持 List 的遍历、stream 和插入删除。
 * node 提供缺失/null 区分和路径诊断；wrap 创建嵌套只读借用，deepCopy 返回独立可变树。
 * 不提供并发同步；序列化使用所属 JsonMapper。
 *
 * @since 1.0.0
 * @see JsonMapper
 */
public interface JsonArray extends JsonValue, List<Object> {
    @Override
    JsonArray deepCopy();
}
