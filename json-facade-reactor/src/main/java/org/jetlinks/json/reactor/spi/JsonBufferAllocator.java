package org.jetlinks.json.reactor.spi;

/**
 * 有界 JSON 输出块的传输层存储 SPI；共享实现须线程安全。
 * 每个分配的缓冲区在转移前由单次订阅串行使用；所有方法均为同步内存操作，不得阻塞 I/O。
 * 失败终止输出；框架负责分帧、预算与清理，Provider 负责 JSON 编码。
 *
 * @param <B> 传输层缓冲区类型
 * @since 1.0.0
 * @see org.jetlinks.json.reactor.JsonStreamMapper#writeChunks
 */
public interface JsonBufferAllocator<B> {

    /**
     * 分配至少指定容量的独立存储；超出请求容量的部分不会被使用。
     * 分配失败须自行释放尚未交付的资源。
     *
     * @param capacity 请求的正整数字节容量
     * @return 非空、独立持有的缓冲区
     */
    B allocate(int capacity);

    /**
     * 向指定偏移写入一个字节，不改变已有字节。
     *
     * @param buffer 当前订阅持有的缓冲区
     * @param offset 已校验、位于请求容量内的零基偏移
     * @param value 待写字节的 int 表示
     */
    void writeByte(B buffer, int offset, int value);

    /**
     * 将源数组的指定区间复制到缓冲区；两个区间均已由适配器校验。
     * 不得持有源数组，不得改变目标中区间以外的字节。
     *
     * @param buffer 当前订阅持有的缓冲区
     * @param offset 目标偏移
     * @param source 借用的非空源数组
     * @param sourceOffset 源偏移
     * @param length 复制字节数
     */
    void writeBytes(B buffer, int offset, byte[] source, int sourceOffset, int length);

    /**
     * 暴露恰好指定长度的可读字节，将所有权转移到返回对象。
     * 可返回原实例；替换时须释放不再需要的存储。失败时原缓冲区仍由调用方持有且可释放。
     *
     * @param buffer 当前订阅持有的缓冲区
     * @param length 有效字节数
     * @return 可发布的非空缓冲区
     */
    B seal(B buffer, int length);

    /**
     * 释放未发布且仍由适配器持有的缓冲区，每次分配或成功 seal 的结果调用一次。
     * 须支持合法空缓冲区，无须支持同一引用重复释放；已发布缓冲区及下游队列、丢弃由下游负责。
     *
     * @param buffer 待释放的非空缓冲区
     */
    void release(B buffer);
}
