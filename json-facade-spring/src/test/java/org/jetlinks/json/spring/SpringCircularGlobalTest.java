package org.jetlinks.json.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.Json;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringCircularGlobalTest {
    @Test
    void nativeMapperCannotRecursivelyBuildItsOwnGlobalFacade() {
        try (var context = SpringContexts.context(true)) {
            context.register(Host.class);
            RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);
            BeanCurrentlyInCreationException cause =
                    SpringContexts.cause(failure, BeanCurrentlyInCreationException.class);
            assertEquals("nativeMapper", cause.getBeanName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JsonFacadeConfiguration.class)
    static class Host {
        @Bean
        ObjectMapper nativeMapper() {
            Json.global();
            return new ObjectMapper();
        }
    }
}
