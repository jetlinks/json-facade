package org.jetlinks.json.spring;

import org.jetlinks.json.JsonOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 普通 Spring 6 应用通过导入此配置适配唯一或 Primary 的 Jackson 2 映射器。
 * 宿主自定义的单例 JsonMapper 优先。{@code json.facade.global-enabled} 默认为 true；
 * 同 ClassLoader 中的隔离或重复上下文应关闭此开关，全局默认实例不随上下文关闭而替换或重置。
 * {@code json.facade.max-value-bytes} 控制增量单条记录的字节预算，不接管 HTTP 编解码器。
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class JsonFacadeConfiguration {
    @Bean
    static SpringJsonBootstrap jsonFacadeBootstrap(Environment environment) {
        // 静态工厂仅依赖 Environment，先于普通 Bean 预留全局入口，避免提前创建
        // ObjectMapper、定制器或应用 BeanPostProcessor。
        return new SpringJsonBootstrap(
                environment.getProperty("json.facade.global-enabled", Boolean.class, true),
                environment.getProperty("json.facade.max-value-bytes", Integer.class,
                        JsonOptions.DEFAULT.maxValueBytes()));
    }
}
