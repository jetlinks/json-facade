package org.jetlinks.json.internal;

import java.util.regex.Pattern;
import org.jetlinks.json.JsonException;

/** Number-only validation for token writers; raw fragments cannot escape into a document. */
public final class JsonNumbers {
    private static final Pattern NUMBER =
            Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private JsonNumbers() {
    }

    public static void validate(String text) {
        if (!NUMBER.matcher(text).matches()) {
            throw new JsonException(JsonException.Kind.SYNTAX, "Invalid JSON number");
        }
    }
}
