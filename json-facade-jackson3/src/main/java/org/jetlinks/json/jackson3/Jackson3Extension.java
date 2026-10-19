package org.jetlinks.json.jackson3;

import java.util.Objects;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;
import org.jetlinks.json.spi.JsonNativeExtension;

/** Jackson 3 build-time escape hatch for native modules/mixins.
 * The callback must not retain the builder or mutate a published mapper.
 * @since 1.0.0
 */
public record Jackson3Extension(Consumer<JsonMapper.Builder> configure) implements JsonNativeExtension {
    public Jackson3Extension {
        Objects.requireNonNull(configure, "configure");
    }

    @Override
    public String providerId() {
        return "jackson3";
    }
}
