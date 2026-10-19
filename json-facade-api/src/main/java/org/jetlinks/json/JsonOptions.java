package org.jetlinks.json;

/** Immutable binding policies and parser budgets. Defaults preserve nulls and numeric precision.
 * Limits apply to materialized values; callers own total stream budgets.
 * @since 1.0.0
 */
public record JsonOptions(boolean losslessNumbers, boolean failUnknownProperties,
                          boolean failDuplicateKeys, boolean omitNulls, boolean pretty,
                          int maxDepth, int maxStringLength, int maxNumberLength, int maxValueBytes) {
    public static final JsonOptions DEFAULT =
            new JsonOptions(true, false, false, false, false, 256, 8 * 1024 * 1024, 1000, 8 * 1024 * 1024);

    public JsonOptions {
        if (maxDepth < 1 || maxStringLength < 1 || maxNumberLength < 1 || maxValueBytes < 1) {
            throw new IllegalArgumentException("JSON limits must be positive");
        }
    }
}
