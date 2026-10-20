package org.jetlinks.json;

/** Entry point for immutable mapper construction and application-lifetime shared instances.
 * Global registration accepts fully configured, thread-safe implementations and never replaces them.
 * References live with this class's ClassLoader; short-lived owners should inject their own instances.
 * @since 1.0.0
 * @see JsonMapperBuilder
 */
public final class Json {
    private static final GlobalJsonMappers GLOBALS = new GlobalJsonMappers(Json::buildDefault);

    private Json() {
    }

    public static JsonMapperBuilder builder() {
        return new JsonMapperBuilder();
    }

    /** Return the shared default, lazily discovering one Provider unless explicitly registered.
     * Configure and register before first use to choose options, modules or a custom implementation.
     * Failed construction publishes nothing and propagates its original failure.
     * @return the stable, thread-safe default mapper
     * @since 1.0.0
     * @see #registerGlobal(JsonMapper)
     */
    public static JsonMapper global() {
        return GLOBALS.mapper();
    }

    /** Look up an explicitly registered, case-sensitive application configuration name.
     * Unknown names fail; they never create an instance or fall back to the default.
     * @param scope nonblank name without leading/trailing whitespace, not a request or tenant ID
     * @return the registered mapper
     * @throws JsonException with CONFIGURATION kind when the name is not registered
     * @since 1.0.0
     * @see #registerGlobal(String, JsonMapper)
     */
    public static JsonMapper global(String scope) {
        return GLOBALS.mapper(scope);
    }

    /** Publish a configured default before its first successful lookup or registration.
     * The caller finishes configuration first; registration does not build, mutate or close it.
     * @param mapper thread-safe implementation obeying JsonMapper's operation/resource contracts
     * @throws JsonException with CONFIGURATION kind if the default is already initialized
     * @since 1.0.0
     * @see JsonMapperBuilder#build()
     */
    public static void registerGlobal(JsonMapper mapper) {
        GLOBALS.register(mapper);
    }

    /** Publish one configured implementation under a stable application-lifetime name.
     * Registrations are independent; duplicates fail even for the same instance. No automatic close,
     * replacement or removal is provided. Short-lived plugins should keep host-owned instances.
     * @param scope nonblank name without leading/trailing whitespace
     * @param mapper thread-safe implementation obeying JsonMapper's operation/resource contracts
     * @throws JsonException with CONFIGURATION kind when the name is already registered
     * @since 1.0.0
     * @see #global(String)
     */
    public static void registerGlobal(String scope, JsonMapper mapper) {
        GLOBALS.register(scope, mapper);
    }

    /** Return the same shared default as global().
     * @return the stable default mapper
     * @since 1.0.0
     * @see #global()
     */
    public static JsonMapper defaultMapper() {
        return global();
    }

    private static JsonMapper buildDefault() {
        return builder().build();
    }
}
