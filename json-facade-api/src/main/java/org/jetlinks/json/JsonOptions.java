package org.jetlinks.json;

/**
 * 可共享的不可变绑定策略与解析预算；默认保留 null 和无类型数字精度。
 * {@code withX} 返回仅更改一项的新配置，仍执行构造校验；调用方自行限制流的总大小。
 *
 * @param losslessNumbers 无类型浮点数使用高精度表示，避免先转为 double
 * @param failUnknownProperties 绑定对象时拒绝未知属性
 * @param failDuplicateKeys 解析时拒绝重复字段名
 * @param omitNulls 通过 Provider 的原生包含策略省略 null
 * @param pretty 同步写入时格式化输出；响应式写入始终紧凑
 * @param maxDepth 正整数，原生解析及写入的嵌套深度上限
 * @param maxStringLength 正整数，原生字符串令牌长度上限，计量由 Provider 决定
 * @param maxNumberLength 正整数，原生数字令牌文本长度上限
 * @param maxValueBytes 正整数，流式单值或事件的字节预算；不限制同步整值读写
 * @since 1.0.0
 */
public record JsonOptions(boolean losslessNumbers, boolean failUnknownProperties,
                          boolean failDuplicateKeys, boolean omitNulls, boolean pretty,
                          int maxDepth, int maxStringLength, int maxNumberLength, int maxValueBytes) {

    /**
     * 默认策略：深度 256、字符串长度 8 Mi、数字长度 1000、增量单值 8 MiB。
     */
    public static final JsonOptions DEFAULT =
            new JsonOptions(true, false, false, false, false, 256, 8 * 1024 * 1024, 1000, 8 * 1024 * 1024);

    public JsonOptions {
        if (maxDepth < 1 || maxStringLength < 1 || maxNumberLength < 1 || maxValueBytes < 1) {
            throw new IllegalArgumentException("JSON limits must be positive");
        }
    }

    /**
     * 复制配置，设置无类型数字的精度策略。
     */
    public JsonOptions withLosslessNumbers(boolean value) {
        return new JsonOptions(value, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置是否拒绝未知属性。
     */
    public JsonOptions withFailUnknownProperties(boolean value) {
        return new JsonOptions(losslessNumbers, value, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置是否拒绝重复字段名。
     */
    public JsonOptions withFailDuplicateKeys(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, value, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置原生 null 包含策略。
     */
    public JsonOptions withOmitNulls(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, value, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置同步输出是否格式化。
     */
    public JsonOptions withPretty(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, value,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置正整数嵌套深度上限。
     */
    public JsonOptions withMaxDepth(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                value, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置正整数原生字符串令牌长度上限。
     */
    public JsonOptions withMaxStringLength(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, value, maxNumberLength, maxValueBytes);
    }

    /**
     * 复制配置，设置正整数原生数字令牌长度上限。
     */
    public JsonOptions withMaxNumberLength(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, value, maxValueBytes);
    }

    /**
     * 复制配置，设置正整数流式单值字节预算；同步整值操作不受此预算限制。
     */
    public JsonOptions withMaxValueBytes(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, value);
    }
}
