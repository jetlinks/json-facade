package org.jetlinks.json.spring;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringConfigurationTest {
    @Test
    void plainSpringSelectsPrimaryNativeBeanAndSupportsIndependentRepeatedContexts() {
        for (int index = 0; index < 2; index++) {
            try (var context = SpringContexts.context(Map.of(
                    "json.facade.global-enabled", false, "json.facade.max-value-bytes", 1024))) {
                context.registerBean("other", ObjectMapper.class, () -> new ObjectMapper());
                context.registerBean("preferred", ObjectMapper.class,
                        () -> new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
                        definition -> definition.setPrimary(true));
                context.register(JsonFacadeConfiguration.class);
                context.refresh();
                JsonMapper facade = context.getBean(JsonMapper.class);
                assertEquals("{\"display_name\":\"n\"}", facade.write(new Name("n")));
                assertEquals(1024, facade.options().maxValueBytes());
                assertEquals(12, facade.readTree("{\"n\":\"12\"}").node("n").asInteger());
                assertEquals(9, facade.createObject().node("missing").node("items").node(0).asInt(9));
                assertSame(facade, context.getBean(JsonMapper.class));
            }
        }
        // The disabled contexts did not reserve or initialize the default.
        JsonMapper owned = Json.builder().provider("jackson2").build();
        Json.registerGlobal(owned);
        assertSame(owned, Json.global());
    }

    @Test
    void ambiguousOrMissingNativeBeanFailsInsteadOfChoosingOrBuildingAnotherMapper() {
        try (var context = SpringContexts.context(false)) {
            context.registerBean("one", ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean("two", ObjectMapper.class, () -> new ObjectMapper());
            context.register(JsonFacadeConfiguration.class);
            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            assertEquals(ObjectMapper.class,
                    SpringContexts.cause(error, NoUniqueBeanDefinitionException.class).getResolvableType().resolve());
        }
        try (var context = SpringContexts.context(false)) {
            context.register(JsonFacadeConfiguration.class);
            RuntimeException error = assertThrows(RuntimeException.class, context::refresh);
            assertEquals(ObjectMapper.class,
                    SpringContexts.cause(error, NoSuchBeanDefinitionException.class).getResolvableType().resolve());
        }
    }

    @Test
    void hostBeanDeclaredAlongsideImportBacksOffWithoutNeedingANativeBean() {
        try (var context = SpringContexts.context(false)) {
            context.register(Host.class);
            context.refresh();
            assertFalse(context.containsBean("jsonFacadeMapper"));
            assertSame(Host.MAPPER, context.getBean(JsonMapper.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(JsonFacadeConfiguration.class)
    static class Host {
        static final JsonMapper MAPPER = Json.builder().provider("jackson2").build();

        @Bean
        JsonMapper hostJson() {
            return MAPPER;
        }
    }

    public record Name(String displayName) {
    }
}
