package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.jetlinks.json.JsonException;

/** Compacts an engine's valid JSON while enforcing the budget before allocating more record bytes.
 * Whitespace inside strings is untouched; this is an output filter, not an input JSON parser.
 */
final class BoundedJsonOutput extends OutputStream {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final int limit;
    private boolean quoted;
    private boolean escaped;

    BoundedJsonOutput(int limit) {
        this.limit = limit;
    }

    @Override
    public void write(int value) {
        int b = value & 0xff;
        if (!quoted && (b == ' ' || b == '\t' || b == '\r' || b == '\n')) {
            return;
        }
        if (output.size() == limit) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON output record exceeds byte budget");
        }
        output.write(b);
        if (escaped) {
            escaped = false;
        } else if (quoted && b == '\\') {
            escaped = true;
        } else if (b == '"') {
            quoted = !quoted;
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        for (int i = offset; i < offset + length; i++) {
            write(bytes[i]);
        }
    }

    byte[] bytes() {
        return output.toByteArray();
    }
}
