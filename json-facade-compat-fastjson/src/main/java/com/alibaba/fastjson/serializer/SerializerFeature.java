package com.alibaba.fastjson.serializer;

/**
 * fastjson 1.x 的选项名与位掩码；这里只保留签名，不实现原生解析或序列化。
 * JSON 入口校验整组选项，不支持的选项在转换和 I/O 前抛错。
 * @deprecated 新代码使用 JsonOperationOptions 或 Provider 的 configureNative。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public enum SerializerFeature {
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    QuoteFieldNames,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    UseSingleQuotes,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteMapNullValue,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteEnumUsingToString,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteEnumUsingName,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    UseISO8601DateFormat,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNullListAsEmpty,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNullStringAsEmpty,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNullNumberAsZero,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNullBooleanAsFalse,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SkipTransientField,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SortField,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteTabAsSpecial,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    PrettyFormat,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteClassName,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableCircularReferenceDetect,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteSlashAsSpecial,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    BrowserCompatible,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteDateUseDateFormat,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    NotWriteRootClassName,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableCheckSpecialChar,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    BeanToArray,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNonStringKeyAsString,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    NotWriteDefaultValue,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    BrowserSecure,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    IgnoreNonFieldGetter,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteNonStringValueAsString,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    IgnoreErrorGetter,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    WriteBigDecimalAsPlain,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    MapSortField;

    /** @deprecated 仅用于旧代码组合位标记，不代表选项可被当前 Provider 实现。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public final int mask = 1 << ordinal();

    /** @deprecated 使用中立操作配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public int getMask() {
        return mask;
    }

    /** @deprecated 使用中立操作配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static boolean isEnabled(int features, SerializerFeature feature) {
        return (features & feature.mask) != 0;
    }

    /** @deprecated 使用中立操作配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static boolean isEnabled(int features, int fieldFeatures, SerializerFeature feature) {
        return isEnabled(features | fieldFeatures, feature);
    }

    /** @deprecated 使用中立操作配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static int config(int features, SerializerFeature feature, boolean enabled) {
        return enabled ? features | feature.mask : features & ~feature.mask;
    }

    /** @deprecated 仅组合旧标记；JSON 入口仍会拒绝不能等效实现的选项。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static int of(SerializerFeature[] features) {
        int mask = 0;
        for (SerializerFeature feature : features) {
            mask |= feature.mask;
        }
        return mask;
    }
}
