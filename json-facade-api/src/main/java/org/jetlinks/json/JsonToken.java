package org.jetlinks.json;

/**
 * 与引擎无关的 JSON 令牌类型；EOF 由 {@link JsonReader#next()} 返回 Java null 表示。
 *
 * @since 1.0.0
 */
public enum JsonToken {

    /**
     * 对象开始。
     */
    START_OBJECT,

    /**
     * 对象结束。
     */
    END_OBJECT,

    /**
     * 数组开始。
     */
    START_ARRAY,

    /**
     * 数组结束。
     */
    END_ARRAY,

    /**
     * 对象字段名。
     */
    FIELD_NAME,

    /**
     * 字符串值。
     */
    STRING,

    /**
     * 数字值。
     */
    NUMBER,

    /**
     * 布尔值。
     */
    BOOLEAN,

    /**
     * 显式 JSON null。
     */
    NULL
}
