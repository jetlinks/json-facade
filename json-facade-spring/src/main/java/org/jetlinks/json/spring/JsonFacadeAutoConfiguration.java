package org.jetlinks.json.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Boot 3 bridge to its managed Jackson 2 configuration; does not change any HTTP codec.
 * Set json.facade.enabled=false to disable this auto-configuration entirely.
 * @since 1.0.0
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnProperty(prefix = "json.facade", name = "enabled", matchIfMissing = true)
@Import(JsonFacadeConfiguration.class)
public class JsonFacadeAutoConfiguration {
}
