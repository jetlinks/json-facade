package org.jetlinks.json.jackson3;

import java.io.IOException;
import java.util.concurrent.Callable;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.StreamConstraintsException;
import org.jetlinks.json.JsonException;

/** Normalizes only engine-owned errors; never embeds source data in the facade message. */
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
        JsonException.Kind kind = JsonException.Kind.BINDING;
        if (cause instanceof StreamConstraintsException) {
            kind = JsonException.Kind.LIMIT;
        } else if (cause instanceof StreamReadException) {
            kind = JsonException.Kind.SYNTAX;
        } else if (cause instanceof tools.jackson.core.exc.JacksonIOException || cause instanceof IOException) {
            kind = JsonException.Kind.IO;
        }
        return new JsonException(kind, "JSON operation failed", cause);
    }
}
