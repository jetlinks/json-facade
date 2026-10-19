package org.jetlinks.json;

/** Stable failure categories for manual JSON operations; the original cause is retained.
 * @since 1.0.0
 */
public final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** The boundary at which an operation failed. */
    public enum Kind { PROVIDER, CONFIGURATION, SYNTAX, BINDING, LIMIT, IO }
    private final Kind kind;

    public JsonException(Kind kind, String message) {
        this(kind, message, null);
    }

    public JsonException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }
}
