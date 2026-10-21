package org.jetlinks.json;

import java.util.Objects;

/** Streaming output format and bounded per-record buffering. Chunks are not record boundaries.
 * @since 1.0.0
 */
public record JsonWriteOptions(Format format, int chunkSize, int maxValueBytes) {
    /** SINGLE_VALUE requires one value; ARRAY brackets records; NDJSON/ROOT_VALUES delimit with LF. */
    public enum Format { SINGLE_VALUE, ARRAY, NDJSON, ROOT_VALUES }

    public JsonWriteOptions {
        Objects.requireNonNull(format, "format");
        if (chunkSize < 1 || maxValueBytes < 1) {
            throw new IllegalArgumentException("Output limits must be positive");
        }
    }

    public static JsonWriteOptions of(Format format) {
        return new JsonWriteOptions(format, 8192, JsonOptions.DEFAULT.maxValueBytes());
    }

    /** Copy a positive chunk size, preserving output format and record budget. */
    public JsonWriteOptions withChunkSize(int value) {
        return new JsonWriteOptions(format, value, maxValueBytes);
    }

    /** Copy a positive record-byte budget, preserving output format and chunk size. */
    public JsonWriteOptions withMaxValueBytes(int value) {
        return new JsonWriteOptions(format, chunkSize, value);
    }
}
