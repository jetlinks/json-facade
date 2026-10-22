package org.jetlinks.json.spi;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 构建期原生配置回调；Provider 每次构建创建并校验独立的原生配置。
 * 回调跨构建复用时须线程安全，不得保留借用的构建器或执行 I/O。
 *
 * @param type 所选引擎提供的非空原生构建器类型
 * @param callback 非空同步配置回调
 * @param <C> 原生构建器类型
 * @since 1.0.0
 * @see JsonConfiguration#configureNative(Object)
 */
public record JsonNativeConfiguration<C>(Class<C> type, Consumer<? super C> callback) {
    public JsonNativeConfiguration {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callback, "callback");
    }

    void applyTo(Object builder) {
        callback.accept(type.cast(builder));
    }
}
