package org.jetlinks.json;

/** Entry point for explicit immutable mapper construction.
 * The lazy default is suitable for independent applications; containers should inject their instances.
 * @since 1.0.0
 */
public final class Json {
    private Json() {
    }

    public static JsonMapperBuilder builder() {
        return new JsonMapperBuilder();
    }

    public static JsonMapper defaultMapper() {
        return DefaultHolder.MAPPER;
    }

    private static final class DefaultHolder {
        private static final JsonMapper MAPPER = builder().build();
    }
}
