package org.jetlinks.json.spring;

import java.util.Objects;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * 用于显式组合 Spring DataBuffer 流的不可变适配器，可并发共享。
 * 每个订阅接管已交付输入及未发布输出；组件视图避免整块输入复制，原生解码和输出复制仍存在。
 * Provider 负责解析和绑定，Reactor 适配器负责分帧、预算及背压；不接管 HTTP 编解码器或全局注册。
 *
 * @since 1.0.0
 * @see JsonStreamMapper
 */
public final class DataBufferJsonStreamMapper {
    private final JsonStreamMapper streams;
    private final DataBufferAllocator allocator;

    private DataBufferJsonStreamMapper(JsonMapper mapper, DataBufferFactory factory) {
        streams = JsonStreamMapper.of(mapper);
        allocator = new DataBufferAllocator(factory);
    }

    /**
     * 复用映射器和线程安全宿主工厂，例如 {@code response.bufferFactory()}。
     * 堆内、直接内存与池化策略由工厂决定；产生需求前不分配缓冲区。
     */
    public static DataBufferJsonStreamMapper of(JsonMapper mapper, DataBufferFactory factory) {
        return new DataBufferJsonStreamMapper(Objects.requireNonNull(mapper, "mapper"),
                Objects.requireNonNull(factory, "factory"));
    }

    /**
     * 接管每个已交付缓冲区的所有权，适用于 Spring 请求体；不额外 retain。
     * 解析完成后释放输入；错误或取消时先结束活动回调再释放，上游负责未交付输入及自己的队列。
     * 字节须稳定至消耗完成，输入位置不变；共享池化缓冲区时，调用方须另行持有自己的引用。
     */
    public <T> Flux<T> read(Publisher<? extends DataBuffer> input, JsonType<T> type, JsonReadOptions options) {
        return streams.readChunks(chunks(input), type, options);
    }

    /**
     * 按类读取，每个输入转移一次所有权。
     *
     * @see #read(Publisher, JsonType, JsonReadOptions)
     */
    public <T> Flux<T> read(Publisher<? extends DataBuffer> input, Class<T> type, JsonReadOptions options) {
        return read(input, JsonType.of(type), options);
    }

    /**
     * 逐令牌读取唯一完整文档；输入所有权同 {@link #read(Publisher, JsonType, JsonReadOptions)}。
     */
    public Flux<JsonEvent> readEvents(Publisher<? extends DataBuffer> input) {
        return streams.readChunkEvents(chunks(input));
    }

    /**
     * 将记录紧凑编码为工厂分配的有界输出块，发布时向下游转移所有权。
     * 例如交给 {@code ServerHttpResponse.writeWith}；其他消费者须释放已消费缓冲区及其队列、丢弃。
     * 失败或取消时适配器释放未发布输出，不将业务输入值当作输出丢弃缓冲区释放。
     */
    public Flux<DataBuffer> write(Publisher<?> values, JsonWriteOptions options) {
        return streams.writeChunks(values, options, allocator);
    }

    /**
     * 增量写入一个 SINGLE_VALUE 结构文档；输出所有权同 {@link #write(Publisher, JsonWriteOptions)}。
     */
    public Flux<DataBuffer> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options) {
        return streams.writeEventChunks(events, options, allocator);
    }

    private static Flux<JsonInputChunk> chunks(Publisher<? extends DataBuffer> input) {
        Objects.requireNonNull(input, "input");
        // 在 onNext 内转移所有权，避免融合延迟取值超出源的交付生命周期。
        return Flux.from(input).hide().map(DataBufferInputChunk::new);
    }
}
