package org.jetlinks.json.spi;

import java.util.List;
import java.util.Objects;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonOptions;

/**
 * Provider 共享的不可变构建输入，列表在构造时防御性复制。
 * 模块和原生回调自身须线程安全，且仅在构建阶段修改配置。
 *
 * @param options 非空通用策略
 * @param modules 按注册顺序执行的非空模块列表
 * @param nativeConfigurations 按注册顺序执行的非空原生回调列表
 * @since 1.0.0
 */
public record JsonConfiguration(JsonOptions options, List<JsonModule> modules,
                                List<JsonNativeConfiguration<?>> nativeConfigurations) {
    public JsonConfiguration {
        Objects.requireNonNull(options, "options");
        modules = List.copyOf(modules);
        nativeConfigurations = List.copyOf(nativeConfigurations);
    }

    /**
     * 在构建线程按顺序执行原生回调，执行前先校验所有目标类型。
     * Provider 每次构建使用全新、尚未发布的原生构建器调用一次，随后校验通用策略及所有权再发布映射器。
     *
     * @param builder 非空、仅本次构建使用的原生构建器，每个回调仅可临时借用
     * @throws JsonException 类型不匹配或回调失败时抛出，类别为 CONFIGURATION，不自动重试
     * @since 1.0.0
     * @see org.jetlinks.json.JsonMapperBuilder#configureNative(Class, java.util.function.Consumer)
     */
    public void configureNative(Object builder) {
        Objects.requireNonNull(builder, "builder");
        // 先校验整批类型，避免部分回调执行后才发现不兼容。
        for (JsonNativeConfiguration<?> configuration : nativeConfigurations) {
            if (!configuration.type().isInstance(builder)) {
                throw new JsonException(JsonException.Kind.CONFIGURATION,
                        "Native JSON configuration requires " + configuration.type().getName()
                                + "; selected Provider supplies " + builder.getClass().getName());
            }
        }
        for (JsonNativeConfiguration<?> configuration : nativeConfigurations) {
            try {
                configuration.applyTo(builder);
            } catch (RuntimeException error) {
                throw new JsonException(JsonException.Kind.CONFIGURATION,
                        "Native JSON configuration callback failed", error);
            }
        }
    }
}
