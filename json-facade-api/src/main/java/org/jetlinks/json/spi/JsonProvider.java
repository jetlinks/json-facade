package org.jetlinks.json.spi;

import org.jetlinks.json.JsonMapper;

/**
 * 通过 ServiceLoader 发现或显式传入的 JSON 引擎 SPI，可跨构建复用。
 * Provider 负责原生解析、绑定、编码及操作资源；不注册全局实例，不依赖 Spring 或 Reactor。
 *
 * @since 1.0.0
 * @see org.jetlinks.json.JsonMapperBuilder
 */
public interface JsonProvider {

    /**
     * 返回稳定、非空且唯一的标识，例如 {@code jackson2}，不得有副作用。
     * 选择阶段同步调用，须支持并发构建；不能依赖类路径发现顺序。
     *
     * @return Provider 标识
     * @since 1.0.0
     */
    String id();

    /**
     * 同步构建已完成配置、不可变且可并发共享的映射器，不执行外部 I/O。
     * <p>须遵循全部 facade 契约，包括独立增量会话；不支持或不匹配的配置应立即失败，不得静默忽略或回退。
     * 使用新建的原生构建器执行 {@link JsonConfiguration#configureNative(Object)}，
     * 回调结束后校验通用策略与所有权；不得保留或暴露可变构建状态。
     *
     * @param configuration 非空、不可变的选项及有序回调，不得修改其成员
     * @return 非空、配置独立且支持并发操作的映射器
     * @throws org.jetlinks.json.JsonException 不支持构建或配置时抛出
     * @since 1.0.0
     * @see JsonMapper#readSession
     */
    JsonMapper create(JsonConfiguration configuration);
}
