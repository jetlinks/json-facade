package org.jetlinks.json;

import java.io.Serializable;
import java.util.Objects;

/**
 * 统一异常和自定义断言工厂共享的失败元数据，不持有 JSON 节点或值。
 * 位置为 null 表示未知，空 Pointer 表示根；原始异常保留引用，其可变状态仍可见。
 *
 * @param kind 失败类别
 * @param reason 访问或校验的细分原因
 * @param message 非空错误描述
 * @param pointer 可空的机器可读位置
 * @param displayPath 可空的展示路径
 * @param cause 可空的原始异常
 * @since 1.0.0
 * @see JsonValue#requireNonNull(java.util.function.Function)
 */
public record JsonFailure(JsonException.Kind kind, Reason reason, String message,
                          JsonPointer pointer, String displayPath, Throwable cause) implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 访问或校验失败的细分原因；其他 JSON 操作使用 UNSPECIFIED。
     */
    public enum Reason {
        /**
         * 不属于树访问或断言细分原因。
         */
        UNSPECIFIED,

        /**
         * 路径不存在。
         */
        MISSING,

        /**
         * 存在但为 JSON null。
         */
        NULL,

        /**
         * 值形状不符合对象或数组等访问要求。
         */
        TYPE_MISMATCH,

        /**
         * Provider 类型转换失败。
         */
        CONVERSION,

        /**
         * 用户条件断言失败。
         */
        ASSERTION
    }

    public JsonFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    /**
     * 返回原始异常引用，与 {@link Throwable#getCause()} 一致，不复制其可变状态。
     */
    public Throwable cause() {
        return cause;
    }
}
