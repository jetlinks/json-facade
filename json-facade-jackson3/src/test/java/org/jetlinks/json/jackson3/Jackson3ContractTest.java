package org.jetlinks.json.jackson3;

import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonMapperBuilder;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.tck.JsonMapperContract;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Jackson3ContractTest extends JsonMapperContract {
    @Override
    protected JsonMapper mapper(JsonOptions options, JsonModule... modules) {
        JsonMapperBuilder builder = Json.builder().options(options).provider(new Jackson3Provider());
        for (JsonModule module : modules) {
            builder.module(module);
        }
        return builder.build();
    }

    @Test
    void discoversProviderAndAppliesNativeExtensionBeforePublication() {
        assertEquals("jackson3", new Jackson3Provider().id());
        assertEquals(2, Json.builder().provider("jackson3")
                .extension(new Jackson3Extension(builder -> builder.disable(
                        tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .build().read("2", Integer.class));
        assertEquals(3, Json.defaultMapper().read("3", Integer.class));
        assertThrows(NullPointerException.class, () -> new Jackson3Extension(null));
        assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class,
                () -> Json.builder().provider(new Jackson3Provider()).extension(() -> "jackson3").build()).kind());
    }
}
