package org.jetlinks.json;

import java.util.Objects;

/** Per-operation framing, selection, null policy and record budget.
 * A selection belongs to a single document and is extracted incrementally.
 * @since 1.0.0
 */
public record JsonReadOptions(Mode mode, Selection selection, NullPolicy nullPolicy, int maxValueBytes) {
    /** Root framing. NDJSON requires exactly one value per nonblank LF/CRLF line. */
    public enum Mode { SINGLE_VALUE, ARRAY_ELEMENTS, NDJSON, ROOT_VALUES }
    /** Reactor cannot emit Java null; use JsonValue to preserve JSON null as a value. */
    public enum NullPolicy { FAIL, SKIP }
    /** Select either the pointed value or the direct elements of its array. */
    public record Selection(JsonPointer pointer, boolean arrayElements) {
        public Selection {
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    public JsonReadOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(nullPolicy, "nullPolicy");
        if (maxValueBytes < 1) {
            throw new IllegalArgumentException("maxValueBytes must be positive");
        }
        if (selection != null && mode != Mode.SINGLE_VALUE) {
            throw new IllegalArgumentException("Selection requires SINGLE_VALUE mode");
        }
    }

    public static JsonReadOptions of(Mode mode) {
        return new JsonReadOptions(mode, null, NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /** Select one complete pointed value from a single document, using the default policy and budget. */
    public static JsonReadOptions selectValue(JsonPointer pointer) {
        return new JsonReadOptions(Mode.SINGLE_VALUE, new Selection(pointer, false),
                NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /** Select a pointed array's direct elements from one document, using the default policy and budget. */
    public static JsonReadOptions selectElements(JsonPointer pointer) {
        return new JsonReadOptions(Mode.SINGLE_VALUE, new Selection(pointer, true),
                NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /** Copy the null policy, preserving framing, selection and budget. */
    public JsonReadOptions withNullPolicy(NullPolicy value) {
        return new JsonReadOptions(mode, selection, value, maxValueBytes);
    }

    /** Copy a positive record-byte budget, preserving framing, selection and null policy. */
    public JsonReadOptions withMaxValueBytes(int value) {
        return new JsonReadOptions(mode, selection, nullPolicy, value);
    }
}
