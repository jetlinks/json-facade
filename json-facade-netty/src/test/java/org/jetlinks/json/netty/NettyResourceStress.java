package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.jackson2.Jackson2Provider;
import org.jetlinks.json.jackson3.Jackson3Provider;
import org.jetlinks.json.reactor.JsonStreamMapper;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/** Retains closed leases/subscribers while their former storage exceeds heap/direct limits. */
public final class NettyResourceStress {
    private NettyResourceStress() {
    }

    public static void main(String[] args) {
        JsonMapper mapper = Json.builder().provider(args[0].equals("2")
                ? new Jackson2Provider() : new Jackson3Provider()).build();
        boolean direct = Boolean.parseBoolean(args[1]);
        PooledByteBufAllocator allocator = new PooledByteBufAllocator(direct);
        JsonStreamMapper ordinary = JsonStreamMapper.of(mapper);
        NettyJsonStreamMapper netty = NettyJsonStreamMapper.of(mapper, allocator);
        List<Object> closed = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            ByteBuf buffer = allocator.buffer(4 * 1024 * 1024, 4 * 1024 * 1024);
            buffer.writeByte('[').writeByte('1').writeByte(',');
            buffer.writerIndex(buffer.capacity());
            NettyInputChunk chunk = new NettyInputChunk(buffer);
            BaseSubscriber<Integer> input = new BaseSubscriber<>() { };
            ordinary.readChunks(Flux.just(chunk), JsonType.of(Integer.class),
                    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)).take(1).subscribe(input);
            require(buffer.refCnt() == 0 && chunk.nextBuffer() == null, "Input lease still live");
            closed.add(chunk);
            closed.add(input);
            BaseSubscriber<ByteBuf> output = new BaseSubscriber<>() {
                @Override
                protected void hookOnNext(ByteBuf value) {
                    value.release();
                }

                @Override
                protected void hookOnError(Throwable error) {
                    throw new AssertionError(error);
                }
            };
            netty.write(Flux.just("x".repeat(3 * 1024 * 1024)), new JsonWriteOptions(
                    JsonWriteOptions.Format.NDJSON, 65536, 8 * 1024 * 1024)).take(1).subscribe(output);
            closed.add(output);
            require(allocator.pinnedDirectMemory() + allocator.pinnedHeapMemory() == 0, "Output still pinned");
        }
        Reference.reachabilityFence(closed);
        System.out.printf("{\"engine\":\"%s\",\"mode\":\"closed-resources\",\"direct\":%s,"
                + "\"owners\":%d,\"pinnedBytes\":0}%n", args[0], direct, closed.size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
