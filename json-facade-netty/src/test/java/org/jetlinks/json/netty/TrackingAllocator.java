package org.jetlinks.json.netty;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tracks live references independently of pooled reserved-memory accounting. */
final class TrackingAllocator extends AbstractByteBufAllocator {
    private final List<ByteBuf> allocated = new CopyOnWriteArrayList<>();

    TrackingAllocator(boolean direct) {
        super(direct);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
        allocated.add(buffer);
        return buffer;
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        ByteBuf buffer = UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
        allocated.add(buffer);
        return buffer;
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    void verifyReleased() {
        allocated.forEach(buffer -> assertEquals(0, buffer.refCnt(), "Unreleased allocation"));
    }
}
