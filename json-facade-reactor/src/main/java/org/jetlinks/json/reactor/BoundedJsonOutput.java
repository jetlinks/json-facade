package org.jetlinks.json.reactor;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Objects;
import org.jetlinks.json.JsonException;

/** Enforces the record byte budget before forwarding native compact output in bulk. */
final class BoundedJsonOutput extends OutputStream {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final int limit;

    BoundedJsonOutput(int limit) {
        this.limit = limit;
    }

    @Override
    public void write(int value) {
        checkBudget(1);
        output.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        checkBudget(length);
        output.write(bytes, offset, length);
    }

    private void checkBudget(int length) {
        if (length > limit - output.size()) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON output record exceeds byte budget");
        }
    }

    byte[] bytes() {
        return output.toByteArray();
    }
}
