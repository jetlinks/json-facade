package org.jetlinks.json;

import java.util.Objects;

/** Streaming output format and bounded per-record buffering. Chunks are not record boundaries.
 * @since 1.0.0
 */
public record JsonWriteOptions(Format format, int chunkSize, int maxValueBytes) {
    /** ARRAY brackets all records; the other formats delimit compact records with LF. */
    public enum Format { ARRAY, NDJSON, ROOT_VALUES }

    public JsonWriteOptions {
        Objects.requireNonNull(format, "format");
        if (chunkSize < 1 || maxValueBytes < 1) {
            throw new IllegalArgumentException("Output limits must be positive");
        }
    }

    public static JsonWriteOptions of(Format format) {
        return new JsonWriteOptions(format, 8192, JsonOptions.DEFAULT.maxValueBytes());
    }
}
