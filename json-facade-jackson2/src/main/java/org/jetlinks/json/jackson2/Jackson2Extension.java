package org.jetlinks.json.jackson2;

import java.util.Objects;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jetlinks.json.spi.JsonNativeExtension;

/** Jackson 2 build-time escape hatch for native modules/mixins.
 * The callback must not retain the builder or mutate a published mapper.
 * @since 1.0.0
 */
public record Jackson2Extension(Consumer<JsonMapper.Builder> configure) implements JsonNativeExtension {
    public Jackson2Extension {
        Objects.requireNonNull(configure, "configure");
    }

    @Override
    public String providerId() {
        return "jackson2";
    }
}
