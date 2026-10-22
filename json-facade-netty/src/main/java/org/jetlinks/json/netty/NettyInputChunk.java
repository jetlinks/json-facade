package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import org.jetlinks.json.reactor.spi.JsonInputChunk;

/** Owns exactly one ByteBuf reference and lazily exposes its readable component views. */
final class NettyInputChunk implements JsonInputChunk {
    private ByteBuf buffer;
    private ByteBuffer[] views;
    private final int readerIndex;
    private final int readableBytes;
    private int next;

    NettyInputChunk(ByteBuf buffer) {
        this.buffer = buffer;
        readerIndex = buffer.readerIndex();
        readableBytes = buffer.readableBytes();
    }

    @Override
    public ByteBuffer nextBuffer() {
        if (buffer == null || next < 0) {
            return null;
        }
        if (views == null) {
            // CompositeByteBuf.nioBuffer() may merge/copy; nioBuffers() preserves component storage.
            int count = buffer.nioBufferCount();
            if (count == 0) {
                throw new UnsupportedOperationException("ByteBuf does not expose NIO views");
            }
            if (count == 1) {
                next = -1;
                return buffer.nioBuffer(readerIndex, readableBytes);
            }
            views = buffer.nioBuffers(readerIndex, readableBytes);
        }
        return next == views.length ? null : views[next++];
    }

    @Override
    public void close() {
        ByteBuf owned = buffer;
        buffer = null;
        views = null;
        if (owned != null) {
            owned.release();
        }
    }
}
