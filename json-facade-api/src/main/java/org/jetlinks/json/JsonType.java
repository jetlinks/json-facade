package org.jetlinks.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** JDK-only type token. Capture nested generics with an anonymous subclass.
 * Immutable and safe to share; never contains an engine's JavaType.
 * @param <T> the bound Java type
 * @since 1.0.0
 */
public class JsonType<T> {
    private final Type type;

    protected JsonType() {
        Type parent = getClass().getGenericSuperclass();
        // Validate on access: an extensible type must not throw while partially constructed.
        type = parent instanceof ParameterizedType parameterized
                && parameterized.getRawType() == JsonType.class
                ? parameterized.getActualTypeArguments()[0] : null;
    }

    private JsonType(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> JsonType<T> of(Class<T> type) {
        return new JsonType<>(type);
    }

    public static JsonType<?> of(Type type) {
        return new JsonType<>(type);
    }

    public static <T> JsonType<List<T>> listOf(JsonType<T> element) {
        return new JsonType<>(new GenericType(List.class, element.type()));
    }

    public static <K, V> JsonType<Map<K, V>> mapOf(JsonType<K> key, JsonType<V> value) {
        return new JsonType<>(new GenericType(Map.class, key.type(), value.type()));
    }

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
