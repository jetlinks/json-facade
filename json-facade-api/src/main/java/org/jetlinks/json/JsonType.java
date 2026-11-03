package org.jetlinks.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 描述带泛型的读取目标，避免读取 List/Map 时丢失元素类型。
 * 普通 DTO 可直接传 Class；嵌套集合用匿名子类或组合工厂：
 * <pre>{@code
 * JsonType<List<Integer>> integers = JsonType.listOf(JsonType.of(Integer.class));
 * List<Integer> values = mapper.read("[1,2]", integers);
 * JsonType<Map<String, List<Integer>>> grouped = new JsonType<>() {};
 * Map<String, List<Integer>> result = mapper.read("{\"values\":[1,2]}", grouped);
 * }</pre>
 * 类型对象不可变、可复用，只保存 JDK Type，不保存引擎类型或映射器。
 * 不要在泛型方法里用 {@code new JsonType<List<T>>() {}} 捕获运行时元素类型；
 * 此时 T 仍是类型变量，应传入具体 JsonType 后使用 listOf 组合。
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
     * 将 Class 作为组合类型的基础，例如 {@code JsonType.listOf(JsonType.of(Integer.class))}。
     * 不解析类声明中的泛型参数；{@code JsonType.of(List.class)} 不包含元素类型。
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
     * 指定 List 元素类型，适合读取一批 DTO 或嵌套数组。
     * <pre>{@code
     * var type = JsonType.listOf(JsonType.listOf(JsonType.of(Integer.class)));
     * List<List<Integer>> rows = mapper.read("[[1,2],[3]]", type);
     * }</pre>
     */
    public static <T> JsonType<List<T>> listOf(JsonType<T> element) {
        return new JsonType<>(new GenericType(List.class, element.type()));
    }

    /**
     * 指定 Map 键值类型，适合读取以标识分组的数据。
     * <pre>{@code
     * var type = JsonType.mapOf(JsonType.of(String.class), JsonType.listOf(JsonType.of(Integer.class)));
     * Map<String, List<Integer>> groups = mapper.read("{\"group-a\":[1,2]}", type);
     * }</pre>
     * JSON 对象键的具体绑定规则由 Provider 决定；通常使用 String 键。
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
