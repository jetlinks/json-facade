package org.jetlinks.json.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringFailedGlobalTest {
    @Test
    void failedStartupKeepsReservationButDropsTheDeadContextInsteadOfFallingBack() {
        try (var context = SpringContexts.context(true)) {
            context.registerBean("one", ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean("two", ObjectMapper.class, () -> new ObjectMapper());
            context.register(JsonFacadeConfiguration.class);
            RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);
            SpringContexts.cause(failure, NoUniqueBeanDefinitionException.class);
        }
        JsonException closed = assertThrows(JsonException.class, Json::global);
        assertEquals(JsonException.Kind.CONFIGURATION, closed.kind());
        assertTrue(closed.getMessage().contains("context is closed"));
        assertThrows(JsonException.class,
                () -> Json.registerGlobal(Json.builder().provider("jackson2").build()));
    }
}
