package org.jetlinks.json;

/**
 * 手动 JSON 操作的统一异常，提供稳定失败分类、诊断位置及原始异常。
 *
 * @since 1.0.0
 * @see JsonFailure
 */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 操作失败的边界类别。
     */
    public enum Kind {
        /**
         * Provider 发现或选择失败。
         */
        PROVIDER,

        /**
         * 配置或全局注册不符合契约。
         */
        CONFIGURATION,

        /**
         * JSON 语法或文档结构错误。
         */
        SYNTAX,

        /**
         * Java 类型绑定失败。
         */
        BINDING,

        /**
         * 超过解析或流式记录预算。
         */
        LIMIT,

        /**
         * 底层输入输出失败。
         */
        IO,

        /**
         * 树访问或用户断言失败。
         */
        VALIDATION
    }
    private final JsonFailure failure;

    /**
     * 创建指定类别和描述的异常，不含原因与位置信息。
     */
    public JsonException(Kind kind, String message) {
        this(kind, message, null);
    }

    /**
     * 创建指定类别的异常，保留原始 cause，位置信息未知。
     */
    public JsonException(Kind kind, String message, Throwable cause) {
        this(new JsonFailure(kind, JsonFailure.Reason.UNSPECIFIED, message, null, null, cause));
    }

    /**
     * 从结构化元数据创建统一异常；自定义断言工厂也可使用同一份元数据。
     */
    public JsonException(JsonFailure failure) {
        super(failure.message(), failure.cause());
        this.failure = failure;
    }

    /**
     * 返回操作失败类别。
     */
    public Kind kind() {
        return failure.kind();
    }

    /**
     * 返回结构化失败信息。
     */
    public JsonFailure failure() {
        return failure;
    }

    /**
     * 返回访问或校验的细分原因。
     */
    public JsonFailure.Reason reason() {
        return failure.reason();
    }

    /**
     * 返回失败位置；未知时为 null，空 Pointer 表示根。
     */
    public JsonPointer pointer() {
        return failure.pointer();
    }

    /**
     * 返回便于阅读的诊断路径；未知时为 null。
     */
    public String displayPath() {
        return failure.displayPath();
    }

    /**
     * 为转换失败的相对位置添加基址，返回新异常，不修改可能被共享的原异常。
     * 保留原始 cause、堆栈和 suppressed 异常，不额外嵌套 facade 异常层。
     */
    public JsonException at(JsonPointer base, String display) {
        java.util.List<String> segments = new java.util.ArrayList<>(base.segments());
        if (pointer() != null) {
            segments.addAll(pointer().segments());
        }
        String suffix = displayPath();
        String fullDisplay = display;
        if (suffix != null && !suffix.equals("$")) {
            fullDisplay = display.equals("$") ? suffix : display + (suffix.startsWith("[") ? "" : ".") + suffix;
        }
        JsonFailure.Reason reason = reason() == JsonFailure.Reason.UNSPECIFIED && kind() == Kind.BINDING
                ? JsonFailure.Reason.CONVERSION : reason();
        JsonException result = new JsonException(new JsonFailure(kind(), reason, getMessage(),
                new JsonPointer(segments), fullDisplay, getCause()));
        result.setStackTrace(getStackTrace());
        for (Throwable suppressed : getSuppressed()) {
            result.addSuppressed(suppressed);
        }
        return result;
    }
}
