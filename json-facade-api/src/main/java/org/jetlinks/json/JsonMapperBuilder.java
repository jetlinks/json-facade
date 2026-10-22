package org.jetlinks.json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonNativeConfiguration;
import org.jetlinks.json.spi.JsonProvider;

/**
 * 单次配置过程使用的映射器构建器，不支持并发修改；构建结果可并发共享。
 * 未显式选择 Provider 时须恰好发现一个实现；构建不修改全局实例。
 *
 * @since 1.0.0
 */
public final class JsonMapperBuilder {
    private JsonOptions options = JsonOptions.DEFAULT;
    private final List<JsonModule> modules = new ArrayList<>();
    private final List<JsonNativeConfiguration<?>> nativeConfigurations = new ArrayList<>();
    private JsonProvider provider;
    private String providerId;
    private ClassLoader classLoader = Json.class.getClassLoader();

    /**
     * 设置非空通用策略；未设置时使用 {@link JsonOptions#DEFAULT}。
     */
    public JsonMapperBuilder options(JsonOptions value) {
        options = Objects.requireNonNull(value, "options");
        return this;
    }

    /**
     * 按顺序追加非空模块；重复模块 ID 在构建时失败。
     */
    public JsonMapperBuilder module(JsonModule value) {
        modules.add(Objects.requireNonNull(value, "module"));
        return this;
    }

    /**
     * 在每次构建时按注册顺序配置所选 Provider 的原生构建器。
     * <p>先检查所有目标类型，再执行回调；通用策略仍以 {@link JsonOptions} 为准，冲突在构建时失败。
     * 回调同步执行，不得保留借用的原生构建器。
     *
     * @param type 所选 Provider 提供的原生构建器类型
     * @param configure 同步构建回调；跨构建复用时须线程安全
     * @param <C> 原生构建器类型
     * @return 当前构建器
     * @since 1.0.0
     * @see JsonConfiguration#configureNative(Object)
     */
    public <C> JsonMapperBuilder configureNative(Class<C> type, Consumer<? super C> configure) {
        nativeConfigurations.add(new JsonNativeConfiguration<>(type, configure));
        return this;
    }

    /**
     * 直接指定 Provider 实例，覆盖之前按 ID 的选择。
     */
    public JsonMapperBuilder provider(JsonProvider value) {
        provider = Objects.requireNonNull(value, "provider");
        providerId = null;
        return this;
    }

    /**
     * 指定 ServiceLoader 发现的 Provider ID，覆盖之前的实例选择；构建时须唯一匹配。
     */
    public JsonMapperBuilder provider(String id) {
        providerId = Objects.requireNonNull(id, "providerId");
        provider = null;
        return this;
    }

    /**
     * 指定发现 Provider 的 ClassLoader；默认使用 {@link Json} 的 ClassLoader。
     */
    public JsonMapperBuilder classLoader(ClassLoader value) {
        classLoader = Objects.requireNonNull(value, "classLoader");
        return this;
    }

    /**
     * 按当前配置构建独立映射器；Provider 不唯一、模块重复或配置不支持时失败。
     */
    public JsonMapper build() {
        JsonProvider selected = provider == null ? discover() : provider;
        Set<String> ids = new HashSet<>();
        for (JsonModule module : modules) {
            if (!ids.add(module.id())) {
                throw new JsonException(JsonException.Kind.CONFIGURATION, "Duplicate JSON module: " + module.id());
            }
        }
        return selected.create(new JsonConfiguration(options, modules, nativeConfigurations));
    }

    private JsonProvider discover() {
        List<JsonProvider> candidates = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonProvider candidate : ServiceLoader.load(JsonProvider.class, classLoader)) {
            if (!ids.add(candidate.id())) {
                throw new JsonException(JsonException.Kind.PROVIDER, "Duplicate JSON provider: " + candidate.id());
            }
            if (providerId == null || providerId.equals(candidate.id())) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() != 1) {
            throw new JsonException(JsonException.Kind.PROVIDER,
                    "Select exactly one JSON provider; candidates=" + candidates.size());
        }
        return candidates.get(0);
    }
}
