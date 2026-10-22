package org.jetlinks.json.spring;

import org.jetlinks.json.reactor.spi.JsonBufferAllocator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;

/** Allocates bounded output through the host factory without a separate pool or intermediate array. */
final class DataBufferAllocator implements JsonBufferAllocator<DataBuffer> {
    private final DataBufferFactory factory;

    DataBufferAllocator(DataBufferFactory factory) {
        this.factory = factory;
    }

    @Override
    public DataBuffer allocate(int capacity) {
        return factory.allocateBuffer(capacity);
    }

    @Override
    public void writeByte(DataBuffer buffer, int offset, int value) {
        buffer.writePosition(offset).write((byte) value);
    }

    @Override
    public void writeBytes(DataBuffer buffer, int offset, byte[] source, int sourceOffset, int length) {
        buffer.writePosition(offset).write(source, sourceOffset, length);
    }

    @Override
    public DataBuffer seal(DataBuffer buffer, int length) {
        return buffer.writePosition(length).readPosition(0);
    }

    @Override
    public void release(DataBuffer buffer) {
        DataBufferUtils.release(buffer);
    }
}
