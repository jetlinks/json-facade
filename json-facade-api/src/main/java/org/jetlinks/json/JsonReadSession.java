package org.jetlinks.json;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 接收分段 UTF-8 字节并逐条返回结果的解析会话，适合自行对接网络输入或不使用 Reactor 的宿主。
 * 每次 read 最多返回一个值；NEED_INPUT 表示应供给下一段，END 表示解析结束。
 * 下面将跨两段的数组读为两个整数：
 * <pre>{@code
 * var chunks = java.util.List.of(
 *     java.nio.ByteBuffer.wrap("[1,".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
 *     java.nio.ByteBuffer.wrap("2]".getBytes(java.nio.charset.StandardCharsets.UTF_8))).iterator();
 * var values = new java.util.ArrayList<Integer>();
 * try (var session = mapper.readSession(Integer.class,
 *         JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
 *     boolean ended = false;
 *     while (true) {
 *         var result = session.read();
 *         if (result.state() == JsonReadSession.State.VALUE) {
 *             values.add(result.value());
 *         } else if (result.state() == JsonReadSession.State.END) {
 *             break;
 *         } else if (chunks.hasNext()) {
 *             session.feed(chunks.next()); // 仅在请求更多输入时供给
 *         } else if (!ended) {
 *             ended = true;
 *             session.endInput(); // 上游 EOF，不是某个 chunk 的结束
 *         } else {
 *             throw new IllegalStateException("Input already ended");
 *         }
 *     }
 * }
 * // values 为 [1, 2]；应持续读取到 END，才完成数组尾部和 EOF 校验。
 * }</pre>
 * 每次读取或订阅独立创建，不得并发使用或重入。
 * 会话不创建线程，也不从网络或文件主动取数据；调用方负责供给输入、处理结果和关闭。
 *
 * @param <T> 绑定后的记录类型
 * @since 1.0.0
 * @see JsonMapper#readSession(JsonType, JsonReadOptions)
 */
public interface JsonReadSession<T> extends AutoCloseable {

    /**
     * 上段输入已消耗、可以供给下一段时返回 true，此时原输入缓冲区可以复用或释放。
     * 不表示上游真的有数据，也不表示已到 EOF；通常先 read，收到 NEED_INPUT 后再供给。
     */
    boolean needsInput();

    /**
     * 借用缓冲区剩余字节，直至 {@code needsInput()} 为 true 或会话关闭。
     * 调用方须保持字节及 position、limit、mark 不变；仅在需要输入时调用。
     * 实现须支持堆内、直接、只读和切片缓冲区。
     * 例如 {@code session.feed(java.nio.ByteBuffer.wrap(bytes));}；本方法不是追加到一个可随时修改的内部副本。
     *
     * @param utf8 非空 UTF-8 输入视图，所有权仍属调用方
     */
    void feed(ByteBuffer utf8);

    /**
     * 最多读取一条记录，或报告需要输入、已校验 EOF；不得无界预读结果。
     * VALUE 可携带 Java null；错误抛出 {@link JsonException}，不得静默跳过。
     * 不要通过 {@code result.value() == null} 判断结束，应检查 state，否则会把 JSON null 与 NEED_INPUT/END 混淆。
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
     * 适合高频读取时复用接收器，例如只需返回状态：
     * <pre>{@code
     * JsonReadSession.Receiver<Integer, JsonReadSession.State> receiver = (state, value) -> {
     *     if (state == JsonReadSession.State.VALUE) {
     *         System.out.println(value);
     *     }
     *     return state;
     * };
     * try (var session = mapper.readSession(Integer.class,
     *         JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))) {
     *     session.feed(java.nio.ByteBuffer.wrap("[1]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
     *     session.endInput();
     *     while (session.read(receiver, () -> false) != JsonReadSession.State.END) {
     *         // 此例已供给全部字节；分段上游须在 NEED_INPUT 时继续 feed。
     *     }
     * }
     * }</pre>
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
     * 仅在整个上游真正 EOF 时调用一次，不在每个 chunk 后调用。
     * 之后继续 read 到 END；缺少数组闭合符、未完成字符串等仍会在 read 时失败。
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
