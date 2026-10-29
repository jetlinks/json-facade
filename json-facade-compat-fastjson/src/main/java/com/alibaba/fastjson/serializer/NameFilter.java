package com.alibaba.fastjson.serializer;

/**
 * 仅用于旧过滤器调用点产生明确的不支持错误；不会执行过滤器。
 * @deprecated 使用 Provider Adapter 或原生过滤配置。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
@FunctionalInterface
public interface NameFilter extends SerializeFilter {
    /** @deprecated 兼容层不执行此回调；使用 Provider 扩展。 */
    @Deprecated(since = "1.0", forRemoval = false)
    String process(Object object, String name, Object value);
}
