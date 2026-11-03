package org.jetlinks.json;

import java.util.function.Supplier;

/**
 * 创建映射器或取得应用级共享映射器的入口，适合在应用启动时配置并注入业务组件。
 * <pre>{@code
 * JsonMapper mapper = Json.builder().provider("jackson2").build();
 * String json = mapper.write(java.util.Map.of("id", "dev-1"));
 * }</pre>
 * 若使用全局入口，应在首次使用前完成 {@link #registerGlobal(JsonMapper)}，
 * 后续通过 {@link #global()} 获取；通常一个应用只注册一次。
 * <p>全局仅接受已配置、可并发使用的实例，注册后不可替换、移除或重置，也不自动关闭。
 * 引用与本类的 ClassLoader 同寿命；短生命周期组件应自行持有或注入实例。
 *
 * @since 1.0.0
 * @see JsonMapperBuilder
 */
public final class Json {
    private static final GlobalJsonMappers GLOBALS = new GlobalJsonMappers(Json::buildDefault);

    private Json() {
    }

    /**
     * 创建独立构建器；不修改已发布实例或全局配置。
     */
    public static JsonMapperBuilder builder() {
        return new JsonMapperBuilder();
    }

    /**
     * 获取共享默认实例；未显式注册时，首次调用自动发现唯一 Provider 并构建。
     * 需要定制策略、模块或实现时，应在首次使用前注册；构建失败不发布结果并传播原异常。
     *
     * @return 发布后身份固定、可并发共享的默认映射器
     * @since 1.0.0
     * @see #registerGlobal(JsonMapper)
     */
    public static JsonMapper global() {
        return GLOBALS.mapper();
    }

    /**
     * 查找显式注册的应用配置，名称区分大小写；未知名称失败，不创建实例或回退默认值。
     *
     * @param scope 无首尾空白的非空名称，不应使用请求 ID 或租户 ID
     * @return 已注册的映射器
     * @throws JsonException 名称未注册时，类别为 {@code CONFIGURATION}
     * @since 1.0.0
     * @see #registerGlobal(String, JsonMapper)
     */
    public static JsonMapper global(String scope) {
        return GLOBALS.mapper(scope);
    }

    /**
     * 在首次成功获取或注册前发布默认实例；调用方须事先完成配置。
     * 注册不构建、修改或关闭该实例。
     *
     * @param mapper 可并发共享且遵循 {@link JsonMapper} 资源契约的实现
     * @throws JsonException 默认实例已注册、已初始化或正在解析时，类别为 {@code CONFIGURATION}
     * @since 1.0.0
     * @see JsonMapperBuilder#build()
     */
    public static void registerGlobal(JsonMapper mapper) {
        GLOBALS.register(mapper);
    }

    /**
     * 预留默认实例的延迟解析工厂，适合在依赖注入容器创建 Bean 前注册。
     * <p>首次获取在发布锁外同步调用工厂，可能并发、重复执行；工厂须安全地解析已完成配置的映射器，
     * 单例构建应由所属容器管理。仅发布一个结果，所有成功调用返回同一实例。
     * 失败调用传播原异常；尚无已发布结果时，后续可重试同一工厂，但不能重新注册工厂。
     * <p>同线程递归获取以 {@code CONFIGURATION} 失败。发布后释放工厂引用，后续获取不再调用工厂。
     *
     * @param factory 应用级解析工厂；须支持并发和重复调用，不得递归获取默认实例，
     * 也不得依赖“只执行一次”来管理副作用或资源
     * @throws JsonException 已注册、已初始化或正在解析时，类别为 {@code CONFIGURATION}
     * @since 1.0.0
     * @see #registerGlobal(JsonMapper)
     */
    public static void registerGlobalFactory(Supplier<? extends JsonMapper> factory) {
        GLOBALS.registerFactory(factory);
    }

    /**
     * 按应用级稳定名称发布实例，各名称独立；同名重复注册即使实例相同也失败。
     * 不自动关闭、替换或移除；短生命周期插件应使用宿主持有的实例。
     *
     * @param scope 无首尾空白的非空名称
     * @param mapper 可并发共享且遵循 {@link JsonMapper} 资源契约的实现
     * @throws JsonException 名称已注册时，类别为 {@code CONFIGURATION}
     * @since 1.0.0
     * @see #global(String)
     */
    public static void registerGlobal(String scope, JsonMapper mapper) {
        GLOBALS.register(scope, mapper);
    }

    /**
     * 获取与 {@link #global()} 相同的共享默认实例。
     *
     * @return 发布后身份固定的默认映射器
     * @since 1.0.0
     */
    public static JsonMapper defaultMapper() {
        return global();
    }

    private static JsonMapper buildDefault() {
        return builder().build();
    }
}
