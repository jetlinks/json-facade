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
 * 在应用启动时选择 JSON 引擎、默认策略和绑定扩展，构建后复用返回的映射器。
 * <pre>{@code
 * JsonMapper mapper = Json.builder()
 *     .provider("jackson2")
 *     .options(JsonOptions.DEFAULT.withFailUnknownProperties(true))
 *     .build();
 * }</pre>
 * 未显式选择 Provider 时，运行环境须恰好包含一个实现；同时引入两个引擎时应指定 ID。
 * 构建器不支持并发修改，构建结果可并发共享；build 不修改 {@link Json#global()}。
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
     * 设置此映射器的默认策略，例如严格检查导入 DTO 的未知字段：
     * {@code builder.options(JsonOptions.DEFAULT.withFailUnknownProperties(true));}
     * 未设置时使用 {@link JsonOptions#DEFAULT}；只对一组操作覆盖设置请用 {@link JsonMapper#withOptions}。
     */
    public JsonMapperBuilder options(JsonOptions value) {
        options = Objects.requireNonNull(value, "options");
        return this;
    }

    /**
     * 注册一组业务类型的读写 Adapter，例如设备标识、脱敏字段或业务时间格式。
     * {@code builder.module(deviceModule);} 中的模块实现 {@link JsonModule}，在构建时注册适配器。
     * 按顺序追加，重复模块 ID 会导致构建失败；模块和 Adapter 须可安全共享。
     */
    public JsonMapperBuilder module(JsonModule value) {
        modules.add(Objects.requireNonNull(value, "module"));
        return this;
    }

    /**
     * 在每次构建时按注册顺序配置所选 Provider 的原生构建器。
     * <p>先检查所有目标类型，再执行回调；通用策略仍以 {@link JsonOptions} 为准，冲突在构建时失败。
     * 回调同步执行，不得保留借用的原生构建器。
     * 适合统一 Map 的输出顺序、字段命名或注册原生模块，例如 Jackson 2 默认按键排序：
     * <pre>{@code
     * JsonMapper mapper = Json.builder().provider("jackson2")
     *     .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
     *         builder -> builder.enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS))
     *     .build();
     * String json = mapper.write(java.util.Map.of("b", 2, "a", 1)); // {"a":1,"b":2}
     * }</pre>
     * Jackson 3 使用 tools.jackson 对应的构建器和选项类型。
     * 单次读写的原生选项请用 {@link JsonOperationOptions.Builder#nativeReader} 或
     * {@link JsonOperationOptions.Builder#nativeWriter}。
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
     * 直接指定引擎实现，适合不使用 ServiceLoader 的宿主或自定义 Provider。
     * 例如引入 Jackson 2 模块后可传 {@code new org.jetlinks.json.jackson2.Jackson2Provider()}。
     * 覆盖之前按 ID 的选择。
     */
    public JsonMapperBuilder provider(JsonProvider value) {
        provider = Objects.requireNonNull(value, "provider");
        providerId = null;
        return this;
    }

    /**
     * 用 ID 选择已安装的引擎，例如 {@code Json.builder().provider("jackson3").build()}。
     * 覆盖之前的实例选择；对应模块须在运行环境中，构建时须唯一匹配。
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
     * 按当前配置创建独立、可并发共享的映射器，适合在启动时构建并注入业务组件。
     * 重复 build 会再次执行模块注册和原生回调，不是获取缓存实例。
     * Provider 不唯一、模块重复或配置不支持时失败；不改变全局映射器。
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
