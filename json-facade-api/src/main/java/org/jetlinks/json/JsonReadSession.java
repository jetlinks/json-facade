package org.jetlinks.json;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 按需求驱动的 UTF-8 增量解析 SPI，每次读取或订阅独立创建，不得并发使用或重入。
 * Provider 仅持有解析器状态，不创建线程，不执行外部 I/O；调用方负责供给输入与关闭。
 *
 * @param <T> 绑定后的记录类型
 * @since 1.0.0
 * @see JsonMapper#readSession(JsonType, JsonReadOptions)
 */
public interface JsonReadSession<T> extends AutoCloseable {

    /**
     * 仅在上段输入已消耗、允许供给下一段时返回 true。
     */
    boolean needsInput();

    /**
     * 借用缓冲区剩余字节，直至 {@code needsInput()} 为 true 或会话关闭。
     * 调用方须保持字节及 position、limit、mark 不变；仅在需要输入时调用。
     * 实现须支持堆内、直接、只读和切片缓冲区。
     *
     * @param utf8 非空 UTF-8 输入视图，所有权仍属调用方
     */
    void feed(ByteBuffer utf8);

    /**
     * 最多读取一条记录，或报告需要输入、已校验 EOF；不得无界预读结果。
     * VALUE 可携带 Java null；错误抛出 {@link JsonException}，不得静默跳过。
     *
     * @return 区分值、输入不足和结束的结果
     */
    Result<T> read();

    /**
     * 支持协作式取消的读取；Provider 可在令牌间检查取消状态。
     * 取消时可直接返回 END 而不校验余下文档，调用方仍须关闭会话。
     * 谓词须线程安全，不得阻塞或修改解析器。
     *
     * @param cancelled 表示调用方已不再需要结果的非空谓词
     * @return 下一结果，取消后可为 END
     * @since 1.0.0
     */
    default Result<T> read(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() ? Result.end() : read();
    }

    /**
     * 同步交付一次读取结果，允许 Provider 避免创建 {@link Result} 中间对象。
     * <p>成功读取后在当前线程恰好调用接收器一次，包括 NEED_INPUT 和 END；VALUE 可携带 Java null，
     * 控制状态的值为 null。接收器不得阻塞或重入；其异常原样传播，调用方须关闭失败操作。
     * 默认实现桥接拉取 API；Provider 可让两种形式共用解析逻辑，并始终负责解析器资源。
     *
     * @param receiver 非空结果映射器
     * @param cancelled 非空协作式取消谓词
     * @param <R> 接收器返回类型，允许结果为 null
     * @return 接收器返回值
     * @since 1.0.0
     * @see Receiver
     */
    default <R> R read(Receiver<? super T, R> receiver, BooleanSupplier cancelled) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(cancelled, "cancelled");
        Result<T> result = read(cancelled);
        return receiver.receive(result.state(), result.value());
    }

    /**
     * 仅在真正输入结束时通知一次；后续 {@code read()} 须拒绝未完成令牌或文档。
     */
    void endInput();

    /**
     * 幂等释放解析器及当前记录状态；不得释放调用方内存。
     */
    @Override
    void close();

    /**
     * 与响应式库无关的同步结果接收器，可供串行读取复用并持有已交付的完整值。
     * 不得重入会话，或在调用方仍持有活动解析操作时向下游发送信号。
     * 须区分控制状态与 VALUE(null)。
     *
     * @param <T> 接收的值类型
     * @param <R> 调用方选择的返回类型
     * @since 1.0.0
     * @see #read(Receiver, BooleanSupplier)
     */
    @FunctionalInterface
    interface Receiver<T, R> {
        /**
         * 映射一次读取结果；VALUE 可携带 null，控制状态携带 null。
         *
         * @param state 非空读取状态
         * @param value VALUE 对应的完整值，其他状态为 null
         * @return 调用方选择的结果，允许为 null
         */
        R receive(State state, T value);
    }

    /**
     * 显式区分 JSON null、输入不足和结束的读取结果。
     *
     * @param state 读取状态
     * @param value VALUE 对应的完整值，控制状态下为 null
     * @param <T> 记录类型
     */
    record Result<T>(State state, T value) {
        /**
         * 创建值结果，允许 Java null。
         */
        public static <T> Result<T> value(T value) {
            return new Result<>(State.VALUE, value);
        }

        /**
         * 请求下一段输入。
         */
        public static <T> Result<T> needInput() {
            return new Result<>(State.NEED_INPUT, null);
        }

        /**
         * 标记读取结束；取消路径可未经完整文档校验。
         */
        public static <T> Result<T> end() {
            return new Result<>(State.END, null);
        }
    }

    /**
     * 会话读取状态，与 JSON 值及输入分段边界区分。
     */
    enum State {
        /**
         * 已读取一条完整记录，值可能为 Java null。
         */
        VALUE,

        /**
         * 需要下一段输入。
         */
        NEED_INPUT,

        /**
         * 读取结束；协作式取消时不保证校验剩余内容。
         */
        END
    }
}
