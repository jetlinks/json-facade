package org.jetlinks.json.reactor;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;

/** Bounded synchronous write unit. Full segments transfer ownership without flattening or re-splitting. */
final class SegmentedJsonOutput<B> extends OutputStream {
    private final int chunkSize;
    private final int limit;
    private final BooleanSupplier cancelled;
    private final JsonBufferAllocator<B> allocator;
    private Deque<OutputSegment<B>> ready = new ArrayDeque<>();
    private B current;
    private int capacity;
    private int used;
    private long count;
    private long budget;
    private int tailReserve;
    private boolean discarded;

    SegmentedJsonOutput(int chunkSize, int limit, BooleanSupplier cancelled, JsonBufferAllocator<B> allocator) {
        this.chunkSize = chunkSize;
        this.limit = limit;
        this.cancelled = cancelled;
        this.allocator = allocator;
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
            allocator.writeByte(current, used++, value);
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
            int size = Math.min(capacity - used, remaining);
            allocator.writeBytes(current, used, bytes, position, size);
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
            capacity = (int) Math.min(chunkSize, Math.max(1L, (long) remaining + tailReserve));
            current = allocator.allocate(capacity);
        }
    }

    private void publishFull() {
        if (used == capacity) {
            seal();
        }
    }

    void seal() {
        if (current != null) {
            if (used != 0) {
                B sealed = allocator.seal(current, used);
                current = null;
                ready.addLast(new OutputSegment<>(sealed, allocator));
            } else {
                allocator.release(current);
            }
            current = null;
            used = 0;
        }
    }

    OutputSegment<B> poll() {
        return ready == null ? null : ready.pollFirst();
    }

    void discard() {
        discarded = true;
        B owned = current;
        Deque<OutputSegment<B>> pending = ready;
        current = null;
        ready = null;
        used = 0;
        try {
            if (owned != null) {
                allocator.release(owned);
            }
        } finally {
            if (pending != null) {
                pending.forEach(OutputSegment::close);
            }
        }
    }
}
