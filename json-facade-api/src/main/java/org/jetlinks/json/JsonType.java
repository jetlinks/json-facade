package org.jetlinks.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仅依赖 JDK 的不可变类型令牌，可并发共享，不保存 Provider 的原生类型对象。
 * 嵌套泛型用直接匿名子类捕获，例如 {@code new JsonType<List<String>>() {}}，也可组合工厂方法。
 *
 * @param <T> 目标 Java 类型
 * @since 1.0.0
 */
public class JsonType<T> {
    private final Type type;

    /**
     * 由直接参数化子类捕获类型；未捕获类型时在 {@link #type()} 访问时失败。
     */
    protected JsonType() {
        Type parent = getClass().getGenericSuperclass();
        // 延迟到访问时校验，避免可扩展类型在子类尚未初始化时抛错。
        type = parent instanceof ParameterizedType parameterized
                && parameterized.getRawType() == JsonType.class
                ? parameterized.getActualTypeArguments()[0] : null;
    }

    private JsonType(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * 包装具体类，不解析该类声明中的泛型参数。
     */
    public static <T> JsonType<T> of(Class<T> type) {
        return new JsonType<>(type);
    }

    /**
     * 包装已有反射类型；目标类型须能被所选 Provider 绑定。
     */
    public static JsonType<?> of(Type type) {
        return new JsonType<>(type);
    }

    /**
     * 组合 List 元素类型，可继续嵌套泛型。
     */
    public static <T> JsonType<List<T>> listOf(JsonType<T> element) {
        return new JsonType<>(new GenericType(List.class, element.type()));
    }

    /**
     * 组合 Map 键值类型；JSON 对象键的具体绑定规则由 Provider 决定。
     */
    public static <K, V> JsonType<Map<K, V>> mapOf(JsonType<K> key, JsonType<V> value) {
        return new JsonType<>(new GenericType(Map.class, key.type(), value.type()));
    }

    /**
     * 返回捕获的反射类型；原始类型或间接子类未捕获类型时抛出 {@link IllegalArgumentException}。
     */
    public final Type type() {
        if (type == null) {
            throw new IllegalArgumentException("Use a direct parameterized JsonType subclass");
        }
        return type;
    }

    private static final class GenericType implements ParameterizedType {
        private final Type raw;
        private final List<Type> arguments;

        private GenericType(Type raw, Type... arguments) {
            this.raw = raw;
            this.arguments = List.of(arguments);
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.toArray(Type[]::new);
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType p && raw.equals(p.getRawType())
                    && p.getOwnerType() == null
                    && java.util.Arrays.equals(getActualTypeArguments(), p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return raw.hashCode() ^ arguments.hashCode();
        }
    }
}
