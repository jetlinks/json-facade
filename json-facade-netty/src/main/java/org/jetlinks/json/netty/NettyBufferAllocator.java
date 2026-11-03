package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;

/** Uses the host allocator's heap/direct preference, with no independent pool or growing segments. */
final class NettyBufferAllocator implements JsonBufferAllocator<ByteBuf> {
    private final ByteBufAllocator allocator;

    NettyBufferAllocator(ByteBufAllocator allocator) {
        this.allocator = allocator;
    }

    @Override
    public ByteBuf allocate(int capacity) {
        return allocator.buffer(capacity, capacity);
    }

    @Override
    public void writeByte(ByteBuf buffer, int offset, int value) {
        buffer.setByte(offset, value);
    }

    @Override
    public void writeBytes(ByteBuf buffer, int offset, byte[] source, int sourceOffset, int length) {
        buffer.setBytes(offset, source, sourceOffset, length);
    }

    @Override
    public ByteBuf seal(ByteBuf buffer, int length) {
        return buffer.setIndex(0, length);
    }

    @Override
    public void release(ByteBuf buffer) {
        buffer.release();
    }
}
