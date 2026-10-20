package org.jetlinks.json.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** Early configuration reservation, late bean resolution, and release on failed/closed contexts. */
final class SpringJsonBootstrap implements BeanFactoryPostProcessor, SmartInitializingSingleton, DisposableBean {
    private final boolean globalEnabled;
    private final int maxValueBytes;
    private volatile ConfigurableListableBeanFactory factory;

    SpringJsonBootstrap(boolean globalEnabled, int maxValueBytes) {
        this.globalEnabled = globalEnabled;
        this.maxValueBytes = maxValueBytes;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // All ordinary definitions are now available, including host beans declared alongside @Import.
        if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, JsonMapper.class, true, false).length == 0) {
            if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
                throw configuration("JSON facade requires a bean definition registry or a host JsonMapper bean");
            }
            RootBeanDefinition definition = new RootBeanDefinition(JsonMapper.class,
                    () -> Jackson2Provider.adapt(beanFactory.getBean(ObjectMapper.class), maxValueBytes));
            registry.registerBeanDefinition(BeanDefinitionReaderUtils.uniqueBeanName("jsonFacadeMapper", registry),
                    definition);
        }
        if (globalEnabled) {
            factory = beanFactory;
            Json.registerGlobalFactory(this::resolve);
        }
    }

    private JsonMapper resolve() {
        ConfigurableListableBeanFactory beanFactory = factory;
        if (beanFactory == null) {
            throw configuration("Spring JSON context is closed or its default was already resolved");
        }
        var selected = beanFactory.resolveNamedBean(JsonMapper.class);
        if (!beanFactory.isSingleton(selected.getBeanName())) {
            throw configuration("Global JSON mapper must be a singleton Spring bean");
        }
        factory = null;
        return selected.getBeanInstance();
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (globalEnabled) {
            // Validate even when no business bean used global() while being initialized.
            Json.global();
        }
    }

    @Override
    public void destroy() {
        // A failed startup may leave a reserved factory. Do not retain its dead BeanFactory.
        factory = null;
    }

    private static JsonException configuration(String message) {
        return new JsonException(JsonException.Kind.CONFIGURATION, message);
    }
}
