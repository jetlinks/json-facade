package org.jetlinks.json.spring;

import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Real containers; each global-lifecycle test class runs in its own Surefire JVM. */
final class SpringContexts {
    private SpringContexts() {
    }

    static AnnotationConfigApplicationContext context(boolean globalEnabled) {
        return context(Map.of("json.facade.global-enabled", globalEnabled));
    }

    static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        return context;
    }

    static <T extends Throwable> T cause(Throwable error, Class<T> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        throw new AssertionError("Missing cause " + type.getName(), error);
    }
}
