package org.jetlinks.json.spring;

import org.jetlinks.json.JsonOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Import into a plain Spring 6 application to adapt its unique or primary Jackson 2 mapper.
 * A host-defined singleton JsonMapper takes precedence. json.facade.global-enabled defaults to true;
 * set it to false for isolated/repeated contexts sharing the API ClassLoader. The default is never
 * replaced or reset on context close. json.facade.max-value-bytes controls incremental record bytes.
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class JsonFacadeConfiguration {
    @Bean
    static SpringJsonBootstrap jsonFacadeBootstrap(Environment environment) {
        // Static factory + Environment only: reserve the global before ordinary beans, without
        // prematurely instantiating ObjectMapper, customizers or application BeanPostProcessors.
        return new SpringJsonBootstrap(
                environment.getProperty("json.facade.global-enabled", Boolean.class, true),
                environment.getProperty("json.facade.max-value-bytes", Integer.class,
                        JsonOptions.DEFAULT.maxValueBytes()));
    }
}
