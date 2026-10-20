package org.jetlinks.json;

import java.io.Serializable;
import java.util.Objects;

/** Immutable failure metadata shared by default exceptions and custom assertion factories.
 * A null location means unknown; an empty pointer identifies the root. No node/value is retained.
 * @since 1.0.0
 * @see JsonValue#requireNonNull(java.util.function.Function)
 */
public record JsonFailure(JsonException.Kind kind, Reason reason, String message,
                          JsonPointer pointer, String displayPath, Throwable cause) implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Fine-grained access/validation reason; UNSPECIFIED applies to other JSON operations. */
    public enum Reason { UNSPECIFIED, MISSING, NULL, TYPE_MISMATCH, CONVERSION, ASSERTION }

    public JsonFailure {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    /** Original cause by identity, as with Throwable.getCause; its mutable state is caller-visible. */
    public Throwable cause() {
        return cause;
    }
}
