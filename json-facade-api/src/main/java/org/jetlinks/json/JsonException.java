package org.jetlinks.json;

/** Stable failure categories for manual JSON operations; the original cause is retained.
 * @since 1.0.0
 */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** The boundary at which an operation failed. */
    public enum Kind { PROVIDER, CONFIGURATION, SYNTAX, BINDING, LIMIT, IO, VALIDATION }
    private final JsonFailure failure;

    public JsonException(Kind kind, String message) {
        this(kind, message, null);
    }

    public JsonException(Kind kind, String message, Throwable cause) {
        this(new JsonFailure(kind, JsonFailure.Reason.UNSPECIFIED, message, null, null, cause));
    }

    /** Construct a unified error from structured metadata. Custom factories may use the same metadata. */
    public JsonException(JsonFailure failure) {
        super(failure.message(), failure.cause());
        this.failure = failure;
    }

    public Kind kind() {
        return failure.kind();
    }

    public JsonFailure failure() {
        return failure;
    }

    public JsonFailure.Reason reason() {
        return failure.reason();
    }

    /** Null when no location is known; an empty pointer denotes the root. */
    public JsonPointer pointer() {
        return failure.pointer();
    }

    public String displayPath() {
        return failure.displayPath();
    }

    /** Prefix a conversion's relative native location, preserving cause/stack/suppressed errors.
     * Does not mutate a potentially shared exception or add another facade layer to its cause chain.
     */
    public JsonException at(JsonPointer base, String display) {
        java.util.List<String> segments = new java.util.ArrayList<>(base.segments());
        if (pointer() != null) {
            segments.addAll(pointer().segments());
        }
        String suffix = displayPath();
        String fullDisplay = display;
        if (suffix != null && !suffix.equals("$")) {
            fullDisplay = display.equals("$") ? suffix : display + (suffix.startsWith("[") ? "" : ".") + suffix;
        }
        JsonFailure.Reason reason = reason() == JsonFailure.Reason.UNSPECIFIED && kind() == Kind.BINDING
                ? JsonFailure.Reason.CONVERSION : reason();
        JsonException result = new JsonException(new JsonFailure(kind(), reason, getMessage(),
                new JsonPointer(segments), fullDisplay, getCause()));
        result.setStackTrace(getStackTrace());
        for (Throwable suppressed : getSuppressed()) {
            result.addSuppressed(suppressed);
        }
        return result;
    }
}
