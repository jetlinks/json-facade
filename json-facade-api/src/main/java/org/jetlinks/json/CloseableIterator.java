package org.jetlinks.json;

import java.util.Iterator;

/**
 * 带解析器释放能力的同步迭代器，每次操作独占，不支持并发或重入。
 * <p>元素可以为 Java null，应以 {@code hasNext()} 判断是否耗尽。使用 try-with-resources，
 * 即使提前停止或消费代码抛错也能释放解析器；关闭不会关闭调用方输入流。
 * 完整消费数组才会校验闭合及尾随内容，提前关闭仅校验已读前缀。
 *
 * @param <T> 绑定后的元素类型
 * @since 1.0.0
 * @see JsonMapper#iterateArray(java.io.InputStream, JsonType)
 */
public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {

    /**
     * 幂等释放解析器，关闭后不再有可读元素；不关闭调用方输入流。
     * 清理失败作为未检查异常向外传播。
     */
    @Override
    void close();
}
