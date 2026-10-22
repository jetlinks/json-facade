package org.jetlinks.json.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 将 Boot 3 管理的 Jackson 2 配置桥接到 facade，不修改 HTTP 编解码器。
 * 设置 {@code json.facade.enabled=false} 可完全禁用此自动配置。
 *
 * @since 1.0.0
 * @see JsonFacadeConfiguration
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(ObjectMapper.class)
@ConditionalOnProperty(prefix = "json.facade", name = "enabled", matchIfMissing = true)
@Import(JsonFacadeConfiguration.class)
public class JsonFacadeAutoConfiguration {
}
