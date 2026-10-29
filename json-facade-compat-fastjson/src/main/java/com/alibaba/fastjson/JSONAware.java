package com.alibaba.fastjson;

/**
 * 保留旧对象的 JSON 输出接口。
 * @deprecated 使用 JsonMapper.write。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public interface JSONAware {
    /** @deprecated 使用 JsonMapper.write。 */
    @Deprecated(since = "1.0", forRemoval = false)
    String toJSONString();
}
