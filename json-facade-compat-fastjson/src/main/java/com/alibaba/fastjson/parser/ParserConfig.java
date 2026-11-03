package com.alibaba.fastjson.parser;

/**
 * 仅保留常用源码签名，不创建旧引擎配置或全局注册表；所有入口立即失败。
 * @deprecated 使用 JsonMapper 构建器、JsonOperationOptions 和 Provider 原生配置。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public final class ParserConfig {
    /** @deprecated 不支持旧引擎配置；使用 Provider 原生配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public ParserConfig() {
        throw new UnsupportedOperationException("ParserConfig is not supported; use Provider configuration");
    }

    /** @deprecated 不提供可变全局注册表；使用 Json.global / JsonMapper。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static ParserConfig getGlobalInstance() {
        throw new UnsupportedOperationException("ParserConfig.getGlobalInstance is not supported; use JsonMapper");
    }
}
