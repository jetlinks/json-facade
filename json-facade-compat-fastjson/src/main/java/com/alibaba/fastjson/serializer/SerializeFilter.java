package com.alibaba.fastjson.serializer;

/**
 * 仅用于旧过滤器调用点产生明确的不支持错误；不会执行过滤器。
 * @deprecated 使用 Provider Adapter 或原生过滤配置。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public interface SerializeFilter {
}
