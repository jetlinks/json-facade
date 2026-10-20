package org.jetlinks.json.reactor;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;

/** Bounded synchronous write unit. Full segments transfer ownership without flattening or re-splitting. */
final class SegmentedJsonOutput extends OutputStream {
    private final int chunkSize;
    private final int limit;
    private final BooleanSupplier cancelled;
    private final Deque<byte[]> ready = new ArrayDeque<>();
    private byte[] current;
    private int used;
    private long count;
    private long budget;
    private int tailReserve;
    private boolean discarded;

    SegmentedJsonOutput(int chunkSize, int limit, BooleanSupplier cancelled) {
        this.chunkSize = chunkSize;
        this.limit = limit;
        this.cancelled = cancelled;
    }

    void beginUnit(int framingBytes) {
        beginUnit(framingBytes, 0);
    }

    void beginUnit(int framingBytes, int tailReserve) {
        count = 0;
        budget = (long) limit + framingBytes;
        this.tailReserve = tailReserve;
    }

    @Override
    public void write(int value) {
        if (accept(1)) {
            allocate(1);
            current[used++] = (byte) value;
            publishFull();
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (!accept(length)) {
            return;
        }
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            allocate(remaining);
            int size = Math.min(current.length - used, remaining);
            System.arraycopy(bytes, position, current, used, size);
            used += size;
            position += size;
            remaining -= size;
            publishFull();
        }
    }

    private boolean accept(int length) {
        if (discarded || cancelled.getAsBoolean()) {
            discard();
            return false;
        }
        if (length > budget - count) {
            throw new JsonException(JsonException.Kind.LIMIT, "JSON output value exceeds byte budget");
        }
        count += length;
        return true;
    }

    private void allocate(int remaining) {
        if (current == null) {
            // Size from the native bulk write; a tiny value must not reserve the configured chunk size.
            int capacity = (int) Math.min(chunkSize, Math.max(1L, (long) remaining + tailReserve));
            current = new byte[capacity];
        }
    }

    private void publishFull() {
        if (used == current.length) {
            ready.addLast(current);
            current = null;
            used = 0;
        }
    }

    void seal() {
        if (current != null) {
            if (used != 0) {
                ready.addLast(used == current.length ? current : Arrays.copyOf(current, used));
            }
            current = null;
            used = 0;
        }
    }

    byte[] poll() {
        return ready.pollFirst();
    }

    void discard() {
        discarded = true;
        current = null;
        used = 0;
        ready.clear();
    }
}
