package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.util.concurrent.Callable;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import org.jetlinks.json.JsonException;

/** Normalizes only engine-owned errors; never embeds source data in the facade message. */
final class Jackson2Support {
    private Jackson2Support() {
    }

    static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (JsonException e) {
            throw e;
        } catch (Exception e) {
            throw failure(e);
        }
    }

    private static JsonException failure(Exception cause) {
        JsonException.Kind kind = JsonException.Kind.BINDING;
        if (cause instanceof StreamConstraintsException) {
            kind = JsonException.Kind.LIMIT;
        } else if (cause instanceof JsonParseException) {
            kind = JsonException.Kind.SYNTAX;
        } else if (cause instanceof IOException
                && !(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)) {
            kind = JsonException.Kind.IO;
        }
        return new JsonException(kind, "JSON operation failed", cause);
    }
}
