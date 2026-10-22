package org.jetlinks.json.reactor.spi;

import java.nio.ByteBuffer;

/**
 * 已转移给单次 JSON 流订阅的输入租约，按顺序串行消费。
 * 视图及底层字节须保持稳定直至关闭；解析和绑定仍由 Provider 完成。
 * 发布者负责尚未交付的租约及自己的队列丢弃。
 *
 * @since 1.0.0
 * @see org.jetlinks.json.reactor.JsonStreamMapper#readChunks
 */
public interface JsonInputChunk extends AutoCloseable {

    /**
     * 借出下一个可读区间，全部区间已供给时返回 null；允许空区间。
     * 仅在解析线程、前一区间已消耗后调用；不得阻塞或修改已返回的存储。
     * 失败终止流并关闭当前租约。
     *
     * @return 下一非独占 ByteBuffer 视图，耗尽时为 null
     */
    ByteBuffer nextBuffer();

    /**
     * 消耗完成后释放租约；错误或取消时须先清理活动解析，再释放所有持有引用。
     * 须幂等，不得关闭输入源；回调期间发生的取消会推迟到回调返回后执行本方法。
     */
    @Override
    void close();
}
