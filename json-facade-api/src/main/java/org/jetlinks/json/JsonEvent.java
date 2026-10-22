package org.jetlinks.json;

import java.util.Objects;

/**
 * 流式结构事件，用于逐令牌读取或输出超大包装文档，不聚合整个容器。
 * Token 的文本保留解析器标量表示；Value 则按映射器配置绑定一个预算内的完整值。
 * 事件载体不可变，但绑定的 Java 值可能可变，调用方须保持其稳定直至消费完成。
 *
 * @since 1.0.0
 */
public sealed interface JsonEvent permits JsonEvent.Token, JsonEvent.Value {

    /**
     * 对象开始。
     */
    Token START_OBJECT = new Token(JsonToken.START_OBJECT, null);

    /**
     * 对象结束。
     */
    Token END_OBJECT = new Token(JsonToken.END_OBJECT, null);

    /**
     * 数组开始。
     */
    Token START_ARRAY = new Token(JsonToken.START_ARRAY, null);

    /**
     * 数组结束。
     */
    Token END_ARRAY = new Token(JsonToken.END_ARRAY, null);

    /**
     * JSON null。
     */
    Token NULL = new Token(JsonToken.NULL, null);

    /**
     * 布尔 true。
     */
    Token TRUE = new Token(JsonToken.BOOLEAN, "true");

    /**
     * 布尔 false。
     */
    Token FALSE = new Token(JsonToken.BOOLEAN, "false");

    /**
     * 创建字段名事件，参数不可为 null。
     */
    static Token name(String name) {
        return new Token(JsonToken.FIELD_NAME, name);
    }

    /**
     * 创建字符串事件，参数不可为 null；JSON null 请用 {@link #NULL}。
     */
    static Token string(String value) {
        return new Token(JsonToken.STRING, value);
    }

    /**
     * 创建数字文本事件，输出时校验数字语法；不经浮点转换。
     */
    static Token number(String text) {
        return new Token(JsonToken.NUMBER, text);
    }

    /**
     * 创建完整值事件，使用当前映射器编码；允许 Java null。
     */
    static Value value(Object value) {
        return new Value(value);
    }

    /**
     * 创建令牌事件；结构令牌复用常量，带文本的事件持有不可变字符串。
     */
    static Token token(JsonToken token, String text) {
        if (text != null && token != JsonToken.FIELD_NAME && token != JsonToken.STRING
                && token != JsonToken.NUMBER && token != JsonToken.BOOLEAN) {
            throw new IllegalArgumentException("Structural tokens have no text");
        }
        return switch (token) {
            case START_OBJECT -> START_OBJECT;
            case END_OBJECT -> END_OBJECT;
            case START_ARRAY -> START_ARRAY;
            case END_ARRAY -> END_ARRAY;
            case NULL -> NULL;
            case BOOLEAN -> "true".equals(text) ? TRUE : "false".equals(text) ? FALSE : new Token(token, text);
            default -> new Token(token, text);
        };
    }

    /**
     * 单个中立 JSON 令牌，不包括输入边界或 EOF。
     *
     * @param token 非空令牌类型
     * @param text 字段名或标量文本；结构令牌及 null 令牌须为 null
     */
    record Token(JsonToken token, String text) implements JsonEvent {
        public Token {
            Objects.requireNonNull(token, "token");
            switch (token) {
                case FIELD_NAME, STRING, NUMBER -> Objects.requireNonNull(text, "text");
                case BOOLEAN -> {
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw new IllegalArgumentException("Invalid boolean token");
                    }
                }
                default -> {
                    if (text != null) {
                        throw new IllegalArgumentException("Structural tokens have no text");
                    }
                }
            }
        }
    }

    /**
     * 一个完整绑定值，允许 Java null；大型嵌套结构应拆成多个令牌事件。
     *
     * @param value 待编码的 Java 值，消费完成前须保持稳定
     */
    record Value(Object value) implements JsonEvent {
    }
}
