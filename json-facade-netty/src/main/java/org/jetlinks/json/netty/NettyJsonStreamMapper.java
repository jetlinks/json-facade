package org.jetlinks.json.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.util.Objects;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * 复用已配置映射器和宿主分配器的不可变 Netty 适配器，可并发共享。
 * 输入视图避免整块复制，原生解码和绑定仍有分配，编码器到输出块仍有字节复制。
 * 状态按订阅隔离并遵循背压，不创建调度器、不全量聚合或内部订阅。
 *
 * @since 1.0.0
 * @see JsonStreamMapper
 */
public final class NettyJsonStreamMapper {
    private final JsonStreamMapper streams;
    private final NettyBufferAllocator allocator;

    private NettyJsonStreamMapper(JsonMapper mapper, ByteBufAllocator allocator) {
        streams = JsonStreamMapper.of(mapper);
        this.allocator = new NettyBufferAllocator(allocator);
    }

    /**
     * 复用映射器和通道分配器，包括其堆内、直接内存偏好；订阅前不分配活动缓冲区。
     */
    public static NettyJsonStreamMapper of(JsonMapper mapper, ByteBufAllocator allocator) {
        return new NettyJsonStreamMapper(Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(allocator, "allocator"));
    }

    /**
     * 借用输入，在 {@code onNext} 内额外 retain 一个引用，适配 Reactor Netty {@code receive()}。
     * 源释放原始引用，适配器仅释放自身持有的引用；字节和组件须稳定至消耗完成，索引不变。
     * 交付前经过异步边界须由上游保活；使用本方法时不要再对 {@code receive()} 额外 retain。
     */
    public <T> Flux<T> read(Publisher<? extends ByteBuf> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input, true), type, options);
    }

    /**
     * 按类读取，保持借用输入并在 onNext 内 retain 的语义。
     *
     * @see #read(Publisher, JsonType, JsonReadOptions)
     */
    public <T> Flux<T> read(Publisher<? extends ByteBuf> input, Class<T> type, JsonReadOptions options) {
        return read(input, JsonType.of(type), options);
    }

    /**
     * 接管每个已交付缓冲区的一个引用，不额外 retain；适用于订阅者持有或已 retain 的输入。
     * 上游负责未交付缓冲区及队列丢弃；转移后调用方不得再释放该引用，字节须稳定至消耗完成。
     */
    public <T> Flux<T> readOwned(Publisher<? extends ByteBuf> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input, false), type, options);
    }

    /**
     * 按类读取，每个输入转移一个引用。
     *
     * @see #readOwned(Publisher, JsonType, JsonReadOptions)
     */
    public <T> Flux<T> readOwned(Publisher<? extends ByteBuf> input, Class<T> type, JsonReadOptions options) {
        return readOwned(input, JsonType.of(type), options);
    }

    /**
     * 逐令牌读取唯一完整文档；输入的借用与 retain 语义同 {@link #read(Publisher, JsonType, JsonReadOptions)}。
     */
    public Flux<JsonEvent> readEvents(Publisher<? extends ByteBuf> input) {
        return streams.readChunkEvents(chunks(input, true));
    }

    /**
     * 逐令牌读取唯一文档；引用转移语义同 {@link #readOwned(Publisher, JsonType, JsonReadOptions)}。
     */
    public Flux<JsonEvent> readOwnedEvents(Publisher<? extends ByteBuf> input) {
        return streams.readChunkEvents(chunks(input, false));
    }

    /**
     * 将记录紧凑编码为分配器创建的有界缓冲区，每个输出向下游转移一个引用。
     * Reactor Netty {@code send()} 接管已接受的缓冲区；其他消费者须释放已消费内容及其队列、丢弃。
     * 错误或取消时适配器仅释放未发布缓冲区；绑定值和令牌仍受预算限制。
     */
    public Flux<ByteBuf> write(Publisher<?> values, JsonWriteOptions options) {
        return streams.writeChunks(values, options, allocator);
    }

    /**
     * 增量写入一个 SINGLE_VALUE 结构文档；输出所有权同 {@link #write(Publisher, JsonWriteOptions)}。
     */
    public Flux<ByteBuf> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return streams.writeEventChunks(events, options, allocator);
    }

    private static Flux<JsonInputChunk> chunks(Publisher<? extends ByteBuf> input, boolean retain) {
        Objects.requireNonNull(input, "input");
        // 在 onNext 返回前完成 retain，防止融合将其推迟到源已释放后的队列读取。
        return Flux.from(input).hide().map(buffer -> lease(buffer, retain));
    }

    private static JsonInputChunk lease(ByteBuf buffer, boolean retain) {
        if (retain) {
            buffer.retain();
        }
        return new NettyInputChunk(buffer);
    }
}
