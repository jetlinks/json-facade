package org.jetlinks.json.spring;

import java.nio.ByteBuffer;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

/** Owns one Spring buffer and the iterator guaranteeing its borrowed component views' lifetime. */
final class DataBufferInputChunk implements JsonInputChunk {
    private DataBuffer buffer;
    private DataBuffer.ByteBufferIterator iterator;

    DataBufferInputChunk(DataBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public ByteBuffer nextBuffer() {
        if (buffer == null) {
            return null;
        }
        if (iterator == null) {
            iterator = buffer.readableByteBuffers();
        }
        return iterator.hasNext() ? iterator.next() : null;
    }

    @Override
    public void close() {
        DataBuffer owned = buffer;
        DataBuffer.ByteBufferIterator views = iterator;
        buffer = null;
        iterator = null;
        try {
            if (views != null) {
                // A Provider can borrow a view until consumption or native parser cleanup finishes.
                views.close();
            }
        } finally {
            DataBufferUtils.release(owned);
        }
    }
}
