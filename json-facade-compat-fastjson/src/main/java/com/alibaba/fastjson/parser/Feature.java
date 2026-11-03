package com.alibaba.fastjson.parser;

/**
 * fastjson 1.x 的选项名与位掩码；这里只保留签名，不实现原生解析或序列化。
 * JSON 入口校验整组选项，不支持的选项在转换和 I/O 前抛错。
 * @deprecated 新代码使用 JsonOperationOptions 或 Provider 的 configureNative。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public enum Feature {
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AutoCloseSource,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AllowComment,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AllowUnQuotedFieldNames,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AllowSingleQuotes,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    InternFieldNames,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AllowISO8601DateFormat,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    AllowArbitraryCommas,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    UseBigDecimal,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    IgnoreNotMatch,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SortFeidFastMatch,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableASM,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableCircularReferenceDetect,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    InitStringFieldAsEmpty,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SupportArrayToBean,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    OrderedField,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableSpecialKeyDetect,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    UseObjectArray,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SupportNonPublicField,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    IgnoreAutoType,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    DisableFieldSmartMatch,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SupportAutoType,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    NonStringKeyAsString,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    CustomMapDeserializer,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    ErrorOnEnumNotMatch,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    SafeMode,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    TrimStringFieldValue,
    /** @deprecated 使用中立配置；不支持的选项由 JSON 入口明确拒绝。 */
    @Deprecated(since = "1.0", forRemoval = false)
    UseNativeJavaObject;

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
    public static boolean isEnabled(int features, Feature feature) {
        return (features & feature.mask) != 0;
    }

    /** @deprecated 使用中立操作配置。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static int config(int features, Feature feature, boolean enabled) {
        return enabled ? features | feature.mask : features & ~feature.mask;
    }

    /** @deprecated 仅组合旧标记；JSON 入口仍会拒绝不能等效实现的选项。 */
    @Deprecated(since = "1.0", forRemoval = false)
    public static int of(Feature[] features) {
        int mask = 0;
        for (Feature feature : features) {
            mask |= feature.mask;
        }
        return mask;
    }
}
