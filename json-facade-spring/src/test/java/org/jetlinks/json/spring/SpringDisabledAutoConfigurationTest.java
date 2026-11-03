package org.jetlinks.json.spring;

import java.util.Map;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringDisabledAutoConfigurationTest {
    @Test
    void bootCanDisableTheAdapterWithoutReservingTheDefault() {
        try (var context = SpringContexts.context(Map.of("json.facade.enabled", false))) {
            context.register(Application.class);
            context.refresh();
            assertTrue(context.getBeansOfType(JsonMapper.class).isEmpty());
        }
        JsonMapper owned = Json.builder().provider("jackson2").build();
        Json.registerGlobal(owned);
        assertSame(owned, Json.global());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
    }
}
