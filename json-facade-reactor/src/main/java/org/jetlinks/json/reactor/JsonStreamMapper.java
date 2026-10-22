package org.jetlinks.json.reactor;

import java.nio.ByteBuffer;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;
import org.jetlinks.json.reactor.spi.JsonInputChunk;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * 基于已配置 {@link JsonMapper} 的不可变 Reactor 适配器，可并发共享，每次订阅创建独立状态。
 * <p>框架负责背压、分帧、定位、预算和取消清理，Provider 负责解析、绑定及编码。
 * 不创建调度器、不阻塞 I/O、不内部订阅或全量聚合；单个绑定值仍须完整物化并受预算限制。
 *
 * @since 1.0.0
 * @see JsonReadOptions
 * @see JsonWriteOptions
 */
public interface JsonStreamMapper {

    /**
     * 复用已配置映射器，不重新发现 Provider 或复制配置；创建时不订阅输入。
     */
    static JsonStreamMapper of(JsonMapper mapper) {
        return new DefaultJsonStreamMapper(java.util.Objects.requireNonNull(mapper, "mapper"));
    }

    /**
     * 读取 UTF-8 字节块，按选项解析完整值、数组元素、NDJSON 或根值序列。
     * 发布后的字节数组须一直保持稳定，包括 {@code onNext} 返回后；取消时关闭解析器并取消上游。
     * 结果不得为 Java null，按 {@link JsonReadOptions#nullPolicy()} 失败或跳过。
     */
    <T> Flux<T> readBytes(Publisher<byte[]> input, JsonType<T> type, JsonReadOptions options);

    /**
     * 按类读取字节块，类型包装仅在订阅前执行一次；所有权同泛型重载。
     *
     * @see #readBytes(Publisher, JsonType, JsonReadOptions)
     */
    default <T> Flux<T> readBytes(Publisher<byte[]> input, Class<T> type, JsonReadOptions options) {
        return readBytes(input, JsonType.of(type), options);
    }

    /**
     * 读取任意 ByteBuffer 的剩余字节，不改变其 position、limit 或 mark。
     * 在输入订阅者的 {@code onNext} 内立即复制，再入队；返回后源可复用存储，不推断 retain/release 语义。
     */
    <T> Flux<T> readBuffers(Publisher<? extends ByteBuffer> input, JsonType<T> type, JsonReadOptions options);

    /**
     * 按类读取缓冲区，保持 {@link #readBuffers(Publisher, JsonType, JsonReadOptions)} 的立即复制语义。
     */
    default <T> Flux<T> readBuffers(Publisher<? extends ByteBuffer> input, Class<T> type, JsonReadOptions options) {
        return readBuffers(input, JsonType.of(type), options);
    }

    /**
     * 逐令牌读取唯一完整文档，保留全部结构字段，不聚合容器。
     * 完整令牌仍受映射器限制；输入所有权同 {@link #readBytes(Publisher, JsonType, JsonReadOptions)}。
     */
    Flux<JsonEvent> readEvents(Publisher<byte[]> input);

    /**
     * 从缓冲区逐令牌读取唯一文档；立即复制语义同 {@link #readBuffers(Publisher, JsonType, JsonReadOptions)}。
     */
    Flux<JsonEvent> readBufferEvents(Publisher<? extends ByteBuffer> input);

    /**
     * 接管输入租约，不复制其原始字节；字节须保持稳定直至租约关闭。
     * 每个已交付租约在消耗后关闭；错误或取消时先结束活动解析，再关闭租约。
     * 上游负责未交付租约及自己的队列，不额外推断源的 retain/release 语义。
     */
    <T> Flux<T> readChunks(Publisher<? extends JsonInputChunk> input, JsonType<T> type, JsonReadOptions options);

    /**
     * 按类读取已转移租约；所有权同 {@link #readChunks(Publisher, JsonType, JsonReadOptions)}。
     */
    default <T> Flux<T> readChunks(Publisher<? extends JsonInputChunk> input, Class<T> type, JsonReadOptions options) {
        return readChunks(input, JsonType.of(type), options);
    }

    /**
     * 从已转移租约逐令牌读取唯一文档；所有权同 {@link #readChunks(Publisher, JsonType, JsonReadOptions)}。
     */
    Flux<JsonEvent> readChunkEvents(Publisher<? extends JsonInputChunk> input);

    /**
     * 按需求将记录紧凑编码为有界字节块；记录值在消费完成前须保持稳定。
     * 输出数组归下游独立持有，库不复用。错误或取消时不补结束括号，已发出字节无法回滚。
     */
    Flux<byte[]> writeBytes(Publisher<?> values, JsonWriteOptions options);

    /**
     * 同 {@link #writeBytes(Publisher, JsonWriteOptions)}，返回独立游标的只读缓冲区，无需释放。
     */
    Flux<ByteBuffer> writeBuffers(Publisher<?> values, JsonWriteOptions options);

    /**
     * 按事件增量写入一个结构文档，可输出超大容器包装；仅支持 SINGLE_VALUE 格式。
     * 每个令牌或绑定值仍受字节预算限制，下游需求控制后续事件；事件和值须保持稳定直至消费完成。
     */
    Flux<byte[]> writeEvents(Publisher<? extends JsonEvent> events, JsonWriteOptions options);

    /**
     * 结构化输出为具有独立游标的只读缓冲区；限制同 {@link #writeEvents(Publisher, JsonWriteOptions)}。
     */
    Flux<ByteBuffer> writeEventBuffers(Publisher<? extends JsonEvent> events, JsonWriteOptions options);

    /**
     * 使用传输层分配器编码有界输出块；发布时将缓冲区所有权转移给下游。
     * 下游负责已消费缓冲区及其新增队列、丢弃路径的释放；适配器负责未发布缓冲区的清理。
     * 分配器跨订阅共享，缓冲区和原生状态按订阅隔离。
     */
    <B> Flux<B> writeChunks(Publisher<?> values, JsonWriteOptions options, JsonBufferAllocator<B> allocator);

    /**
     * 按事件写入传输层缓冲区；文档限制同 {@link #writeEvents(Publisher, JsonWriteOptions)}，
     * 所有权和清理同 {@link #writeChunks(Publisher, JsonWriteOptions, JsonBufferAllocator)}。
     */
    <B> Flux<B> writeEventChunks(Publisher<? extends JsonEvent> events, JsonWriteOptions options,
                                JsonBufferAllocator<B> allocator);
}
