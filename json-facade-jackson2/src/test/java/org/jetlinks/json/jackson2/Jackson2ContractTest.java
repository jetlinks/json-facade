package org.jetlinks.json.jackson2;

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

class Jackson2ContractTest extends JsonMapperContract {
    @Override
    protected JsonMapper mapper(JsonOptions options, JsonModule... modules) {
        JsonMapperBuilder builder = Json.builder().options(options).provider(new Jackson2Provider());
        for (JsonModule module : modules) {
            builder.module(module);
        }
        return builder.build();
    }

    @Test
    void discoversProviderAndAppliesNativeExtensionBeforePublication() {
        assertEquals("jackson2", new Jackson2Provider().id());
        assertEquals(2, Json.builder().provider("jackson2")
                .extension(new Jackson2Extension(builder -> builder.disable(
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)))
                .build().read("2", Integer.class));
        assertEquals(3, Json.defaultMapper().read("3", Integer.class));
        assertThrows(NullPointerException.class, () -> new Jackson2Extension(null));
        assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class,
                () -> Json.builder().provider(new Jackson2Provider()).extension(() -> "jackson2").build()).kind());
    }
}
