package com.alibaba.fastjson;

/**
 * 兼容入口的绑定或 I/O 失败；cause 保留原始 JsonException。
 * 不支持的 API/选项使用 UnsupportedOperationException，不会伪装为解析错误。
 * @deprecated 新代码处理 JsonException。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public class JSONException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** @deprecated 新代码处理 JsonException。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONException() {
        super();
    }

    /** @deprecated 新代码处理 JsonException。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONException(String message) {
        super(message);
    }

    /** @deprecated 新代码处理 JsonException，并保留原始异常。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public JSONException(String message, Throwable cause) {
        super(message, cause);
    }
}
