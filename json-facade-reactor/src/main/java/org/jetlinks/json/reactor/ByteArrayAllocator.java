package org.jetlinks.json.reactor;

import java.util.Arrays;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;

/** Ordinary output retains its independently owned, exactly sized byte-array contract. */
enum ByteArrayAllocator implements JsonBufferAllocator<byte[]> {
    INSTANCE;

    @Override
    public byte[] allocate(int capacity) {
        return new byte[capacity];
    }

    @Override
    public void writeByte(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) value;
    }

    @Override
    public void writeBytes(byte[] buffer, int offset, byte[] source, int sourceOffset, int length) {
        System.arraycopy(source, sourceOffset, buffer, offset, length);
    }

    @Override
    public byte[] seal(byte[] buffer, int length) {
        return length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
    }

    @Override
    public void release(byte[] buffer) {
        // Independently owned arrays are reclaimed by the JVM.
    }
}
