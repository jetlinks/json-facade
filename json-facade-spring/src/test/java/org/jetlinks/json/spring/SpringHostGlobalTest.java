package org.jetlinks.json.spring;

import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpringHostGlobalTest {
    @Test
    void hostSingletonWinsWithoutNativeMapperAndStaysPublishedAfterClose() {
        JsonMapper owned;
        try (var context = SpringContexts.context(true)) {
            context.register(Host.class);
            context.refresh();
            owned = context.getBean(JsonMapper.class);
            assertSame(owned, Json.global());
            assertFalse(context.containsBean("jsonFacadeMapper"));
        }
        assertSame(owned, Json.global());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JsonFacadeConfiguration.class)
    static class Host {
        @Bean
        @Primary
        JsonMapper hostMapper() {
            return Json.builder().provider("jackson2").build();
        }

        @Bean
        JsonMapper secondaryMapper() {
            return Json.builder().provider("jackson2").build();
        }
    }
}
