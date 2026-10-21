package org.jetlinks.json;

/** Immutable binding policies and parser budgets. Defaults preserve nulls and numeric precision.
 * Limits apply to materialized values; callers own total stream budgets.
 * withX methods copy one setting, preserving all others and applying the same validation.
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

    /** Copy with the given untyped numeric precision policy. */
    public JsonOptions withLosslessNumbers(boolean value) {
        return new JsonOptions(value, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with the given unknown-property policy. */
    public JsonOptions withFailUnknownProperties(boolean value) {
        return new JsonOptions(losslessNumbers, value, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with the given duplicate-key policy. */
    public JsonOptions withFailDuplicateKeys(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, value, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with the given native null-inclusion policy. */
    public JsonOptions withOmitNulls(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, value, pretty,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with the given synchronous output formatting policy. */
    public JsonOptions withPretty(boolean value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, value,
                maxDepth, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with a positive nesting-depth limit. */
    public JsonOptions withMaxDepth(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                value, maxStringLength, maxNumberLength, maxValueBytes);
    }

    /** Copy with a positive native string-token length limit. */
    public JsonOptions withMaxStringLength(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, value, maxNumberLength, maxValueBytes);
    }

    /** Copy with a positive native number-token length limit. */
    public JsonOptions withMaxNumberLength(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, value, maxValueBytes);
    }

    /** Copy with a positive per-value streaming byte budget; synchronous whole values are not bounded. */
    public JsonOptions withMaxValueBytes(int value) {
        return new JsonOptions(losslessNumbers, failUnknownProperties, failDuplicateKeys, omitNulls, pretty,
                maxDepth, maxStringLength, maxNumberLength, value);
    }
}
