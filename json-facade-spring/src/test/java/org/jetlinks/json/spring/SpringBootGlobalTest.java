package org.jetlinks.json.spring;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringBootGlobalTest {
    @Test
    void bootDiscoveryReusesConfiguredNativePoliciesAndSupportsEarlyBusinessInitialization() throws IOException {
        JsonMapper retained;
        try (var context = SpringContexts.context(Map.of(
                "spring.jackson.property-naming-strategy", "SNAKE_CASE",
                "spring.jackson.default-property-inclusion", "non_empty",
                "spring.jackson.date-format", "yyyy/MM/dd",
                "spring.jackson.time-zone", "UTC"))) {
            context.register(Application.class);
            context.refresh();
            retained = context.getBean(JsonMapper.class);
            assertSame(retained, Json.global());
            assertSame(retained, context.getBean(Business.class).mapper);
            ObjectMapper nativeMapper = context.getBean(ObjectMapper.class);
            Payload payload = new Payload("n", "", new Date(0));
            String expected = "{\"display_name\":\"n\",\"created_at\":\"1970/01/01\"}";
            assertEquals(expected, retained.write(payload));
            assertEquals(nativeMapper.writeValueAsString(payload), retained.write(payload));
            assertEquals("\"post-processed:v\"", retained.write(new Label("v")));
            assertEquals("{\"external_name\":\"n\"}", retained.write(new Name("n")));
            assertFalse(retained.options().losslessNumbers());
            assertFalse(retained.options().failUnknownProperties());
            assertTrue(nativeMapper.isEnabled(JsonParser.Feature.AUTO_CLOSE_SOURCE));
            assertTrue(nativeMapper.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET));
            assertFalse(nativeMapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
            JsonStreamMapper streams = JsonStreamMapper.of(retained);
            List<Name> names = streams.readBytes(Flux.just("[{\"external_".getBytes(StandardCharsets.UTF_8),
                            "name\":\"n\"}]".getBytes(StandardCharsets.UTF_8)), JsonType.of(Name.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).collectList().block();
            assertEquals(List.of(new Name("n")), names);
            String encoded = streams.writeBytes(Flux.fromIterable(names),
                            JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY))
                    .map(bytes -> new String(bytes, StandardCharsets.UTF_8)).collectList()
                    .map(parts -> String.join("", parts)).block();
            assertEquals("[{\"external_name\":\"n\"}]", encoded);
        }
        assertSame(retained, Json.global());
        // A second application cannot silently replace the ClassLoader-owned default.
        try (var second = SpringContexts.context(true)) {
            second.register(Application.class);
            RuntimeException error = assertThrows(RuntimeException.class, second::refresh);
            assertEquals(JsonException.Kind.CONFIGURATION, SpringContexts.cause(error, JsonException.class).kind());
        }
        assertSame(retained, Json.global());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        Business business() {
            return new Business();
        }

        @Bean
        Jackson2ObjectMapperBuilderCustomizer customizer() {
            return builder -> builder.mixIn(Name.class, NameMixin.class);
        }

        @Bean
        static BeanPostProcessor nativePostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof ObjectMapper mapper) {
                        SimpleModule module = new SimpleModule("post-processed");
                        module.addSerializer(Label.class, new JsonSerializer<>() {
                            @Override
                            public void serialize(Label value, JsonGenerator output, SerializerProvider context)
                                    throws IOException {
                                output.writeString("post-processed:" + value.text());
                            }
                        });
                        mapper.registerModule(module);
                    }
                    return bean;
                }
            };
        }
    }

    static final class Business implements InitializingBean {
        private JsonMapper mapper;

        @Override
        public void afterPropertiesSet() {
            mapper = Json.global();
            assertEquals(new Name("early"), mapper.read("{\"external_name\":\"early\"}", Name.class));
        }
    }

    public record Payload(String displayName, String omitted, Date createdAt) {
    }

    public record Label(String text) {
    }

    public record Name(String displayName) {
    }

    abstract static class NameMixin {
        @JsonProperty("external_name")
        abstract String displayName();
    }
}
