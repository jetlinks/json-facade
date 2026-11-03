package org.jetlinks.json.spi;

/**
 * 具名的中立绑定适配器集合；实现须线程安全，可在多个映射器构建中复用。
 * 每次构建在映射器发布前同步注册适配器。
 *
 * @since 1.0.0
 * @see JsonModuleContext
 */
public interface JsonModule {

    /**
     * 返回稳定、非空的诊断标识，在单个映射器配置内唯一。
     * 构建时同步调用，不得执行 I/O 或产生其他副作用。
     *
     * @return 用于拒绝重复注册的模块标识
     * @since 1.0.0
     */
    String id();

    /**
     * 在映射器发布前同步注册精确类型适配器。
     * 实例可跨构建复用，不得执行 I/O 或持有可变上下文。
     *
     * @param context 非空构建期注册表，返回后不得继续持有或修改
     * @throws org.jetlinks.json.JsonException 注册无效时抛出；错误向外传播，不自动重试
     * @since 1.0.0
     * @see JsonModuleContext#add
     */
    void configure(JsonModuleContext context);
}
