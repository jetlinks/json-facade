package org.jetlinks.json.spring;

import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.springframework.beans.factory.config.BeanDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringPrototypeGlobalTest {
    @Test
    void globalRequiresAStableSingletonHostBean() {
        try (var context = SpringContexts.context(true)) {
            context.registerBean("host", JsonMapper.class,
                    () -> Json.builder().provider("jackson2").build(),
                    definition -> definition.setScope(BeanDefinition.SCOPE_PROTOTYPE));
            context.register(JsonFacadeConfiguration.class);
            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            JsonException failure = SpringContexts.cause(error, JsonException.class);
            assertEquals(JsonException.Kind.CONFIGURATION, failure.kind());
            assertTrue(failure.getMessage().contains("singleton"));
        }
    }
}
