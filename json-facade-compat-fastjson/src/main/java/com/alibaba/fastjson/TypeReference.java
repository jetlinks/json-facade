package com.alibaba.fastjson;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.jetlinks.json.JsonType;

/**
 * 捕获直接匿名子类的泛型，例如 new TypeReference&lt;List&lt;Device&gt;&gt;() {}。
 * 只向 JsonType 传递 JDK Type；不实现类型变量替换、原生类型缓存。
 * @deprecated 新代码使用 JsonType。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class TypeReference<T> {
    /** @deprecated 使用 JsonType.listOf(JsonType.of(String.class))。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static final Type LIST_STRING = JsonType.listOf(JsonType.of(String.class)).type();
    private final Type type;

    /** @deprecated 使用直接匿名 JsonType 子类或组合工厂。 */
    @Deprecated(since = "1.0", forRemoval = false)
    protected TypeReference() {
        Type parent = getClass().getGenericSuperclass();
        type = parent instanceof ParameterizedType captured && captured.getRawType() == TypeReference.class
                ? captured.getActualTypeArguments()[0] : null;
    }

    /** @deprecated 仅保留签名，getType 时拒绝旧类型变量替换；使用 JsonType.listOf/mapOf。 */
    @Deprecated(since = "1.0", forRemoval = false)
    protected TypeReference(Type... arguments) {
        type = null;
    }

    /** @deprecated 使用 JsonType.type。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public Type getType() {
        if (type == null) {
            throw JSON.unsupported("TypeReference(Type...) or uncaptured type", "direct JsonType subclass / listOf/mapOf");
        }
        return type;
    }
}
