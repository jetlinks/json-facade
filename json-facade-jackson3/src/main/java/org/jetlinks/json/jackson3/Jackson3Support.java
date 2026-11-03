package org.jetlinks.json.jackson3;

import java.io.IOException;
import java.util.concurrent.Callable;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.StreamConstraintsException;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonFailure;
import org.jetlinks.json.internal.JsonPath;

/** Normalizes engine errors and copies structured locations, without parsing messages/source data. */
final class Jackson3Support {
    private Jackson3Support() {
    }

    static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (Exception e) {
            throw failure(e);
        }
    }

    static JsonException failure(Exception cause) {
        if (cause instanceof JsonException failure) {
            return failure;
        }
        // convertValue may put a native failure inside IllegalArgumentException.
        Throwable nativeError = cause instanceof IllegalArgumentException
                && cause.getCause() instanceof tools.jackson.core.JacksonException
                ? cause.getCause() : cause;
        JsonException.Kind kind = category(nativeError);
        JsonPath path = JsonPath.ROOT;
        boolean known = false;
        if (nativeError instanceof tools.jackson.core.JacksonException mapping) {
            for (var reference : mapping.getPath()) {
                if (reference.getPropertyName() != null) {
                    path = path.field(reference.getPropertyName());
                    known = true;
                } else if (reference.getIndex() >= 0) {
                    path = path.index(reference.getIndex());
                    known = true;
                }
            }
        }
        return new JsonException(new JsonFailure(kind,
                kind == JsonException.Kind.BINDING ? JsonFailure.Reason.CONVERSION : JsonFailure.Reason.UNSPECIFIED,
                "JSON operation failed", known ? path.pointer() : null, known ? path.display() : null, cause));
    }

    private static JsonException.Kind category(Throwable cause) {
        // Native property binding can wrap tokenizer limits/IO; retain its outer path but
        // classify the nested engine failure. Never inspect arbitrary business cause chains.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (cause instanceof tools.jackson.databind.DatabindException && seen.add(cause)
                && (cause.getCause() instanceof tools.jackson.core.JacksonException || cause.getCause() instanceof IOException)) {
            cause = cause.getCause();
        }
        if (cause instanceof StreamConstraintsException) {
            return JsonException.Kind.LIMIT;
        }
        if (cause instanceof StreamReadException) {
            return JsonException.Kind.SYNTAX;
        }
        if (cause instanceof tools.jackson.core.exc.JacksonIOException || cause instanceof IOException) {
            return JsonException.Kind.IO;
        }
        return JsonException.Kind.BINDING;
    }
}
