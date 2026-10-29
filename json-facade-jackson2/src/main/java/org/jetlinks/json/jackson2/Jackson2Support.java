package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.util.concurrent.Callable;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonFailure;
import org.jetlinks.json.internal.JsonPath;

/** Normalizes engine errors and copies structured locations, without parsing messages/source data. */
final class Jackson2Support {
    private Jackson2Support() {
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
                && cause.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException
                ? cause.getCause() : cause;
        JsonException.Kind kind = category(nativeError);
        JsonPath path = JsonPath.ROOT;
        boolean known = false;
        if (nativeError instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
            for (var reference : mapping.getPath()) {
                if (reference.getFieldName() != null) {
                    path = path.field(reference.getFieldName());
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
        while (cause instanceof com.fasterxml.jackson.databind.JsonMappingException && seen.add(cause)
                && cause.getCause() instanceof IOException) {
            cause = cause.getCause();
        }
        if (cause instanceof StreamConstraintsException) {
            return JsonException.Kind.LIMIT;
        }
        if (cause instanceof JsonParseException) {
            return JsonException.Kind.SYNTAX;
        }
        if (cause instanceof IOException
                && !(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)) {
            return JsonException.Kind.IO;
        }
        return JsonException.Kind.BINDING;
    }
}
