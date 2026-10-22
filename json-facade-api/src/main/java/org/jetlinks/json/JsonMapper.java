package org.jetlinks.json;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 配置完成后可并发共享的 JSON 映射器；游标、迭代器和增量会话须按操作独立使用。
 * <p>通过 Provider 完成解析、绑定和编码；仅处理显式调用，不接管 HTTP 编解码器或全局配置。
 *
 * @since 1.0.0
 * @see org.jetlinks.json.spi.JsonProvider
 */
public interface JsonMapper {

    /**
     * 返回不可变的通用策略与预算，流式适配器也使用这些设置。
     * <p>原生快照可能保留更丰富的属性包含、类型转换等规则，本配置不完整描述这些规则。
     */
    JsonOptions options();

    /**
     * 向字节流写入一个根值；目标由调用方关闭。
     */
    void write(Object value, OutputStream output);

    /**
     * 本次调用输出紧凑 JSON，保留绑定模块和其他输出策略，不改变共享的格式化配置。
     * 目标由调用方关闭。
     *
     * @since 1.0.0
     */
    void writeCompact(Object value, OutputStream output);

    /**
     * 向字符流写入一个根值；目标由调用方关闭。
     */
    void write(Object value, Writer output);

    /**
     * 读取并绑定唯一完整的根值，拒绝尾随内容；不关闭输入流。
     */
    <T> T read(InputStream input, JsonType<T> type);

    /**
     * 按类读取唯一完整根值；不关闭输入流。
     *
     * @see #read(InputStream, JsonType)
     */
    default <T> T read(InputStream input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 从字符流读取唯一完整根值，拒绝尾随内容；不关闭输入流。
     */
    <T> T read(Reader input, JsonType<T> type);

    /**
     * 按类从字符流读取唯一完整根值；不关闭输入流。
     *
     * @see #read(Reader, JsonType)
     */
    default <T> T read(Reader input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 按 JSON 数据语义转换 Java 值，不保留源对象身份；字符串作为值转换，不解析为 JSON。
     */
    <T> T convert(Object value, JsonType<T> type);

    /**
     * 按类转换 Java 值，沿用当前绑定配置；字符串作为值转换，不解析为 JSON。
     *
     * @see #convert(Object, JsonType)
     */
    default <T> T convert(Object value, Class<T> type) {
        return convert(value, JsonType.of(type));
    }

    /**
     * 在输入起点创建同步令牌游标；关闭游标不关闭调用方输入流。
     */
    JsonReader reader(InputStream input);

    /**
     * 懒加载根数组的直接元素，使用当前 Provider 逐个绑定；不支持定位嵌套数组。
     * <p>创建时不读取输入；{@code hasNext()} 可等待输入但不绑定元素，{@code next()} 仅绑定一个元素，
     * 结果可以为 Java {@code null}。耗尽时校验闭合及 EOF；耗尽或解析、绑定失败时释放解析器。
     * <p>提前停止仍须关闭迭代器，建议使用 try-with-resources；任何情况下均不关闭调用方输入流。
     * 适用原生令牌与深度限制，不适用增量会话的 {@code maxValueBytes}。
     *
     * @param input 调用方持有的非空输入流
     * @param type 非空元素类型
     * @param <T> 元素类型
     * @return 本次操作独占的迭代器，不支持并发或重入
     * @since 1.0.0
     * @see CloseableIterator
     */
    default <T> CloseableIterator<T> iterateArray(InputStream input, JsonType<T> type) {
        return new org.jetlinks.json.internal.JsonArrayIterator<>(this, input, type);
    }

    /**
     * 按类逐个读取根数组，资源所有权和限制同泛型重载。
     *
     * @since 1.0.0
     * @see #iterateArray(InputStream, JsonType)
     */
    default <T> CloseableIterator<T> iterateArray(InputStream input, Class<T> type) {
        return iterateArray(input, JsonType.of(type));
    }

    /**
     * 创建同步令牌写入器；关闭写入器不关闭调用方输出流。
     */
    JsonWriter writer(OutputStream output);

    /**
     * 创建可连续写值的紧凑写入器，不自动添加根值分隔符；分隔和目标流由调用方管理。
     * 每次操作或订阅独立创建；关闭时不补齐未闭合的容器。
     *
     * @since 1.0.0
     */
    JsonWriter compactWriter(OutputStream output);

    /**
     * 创建单文档增量事件会话，不聚合容器；标量和解析器限制仍生效。
     * 输入借用、取消和释放遵循增量读取会话契约。
     *
     * @since 1.0.0
     * @see JsonReadSession
     */
    JsonReadSession<JsonEvent> eventSession();

    /**
     * 创建独立的非阻塞 UTF-8 解析会话；类型和选项在会话生命周期内固定。
     * 调用方负责串行供给输入、读取结果及关闭会话。
     */
    <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions options);

    /**
     * 按类创建独立的非阻塞解析会话。
     *
     * @see #readSession(JsonType, JsonReadOptions)
     */
    default <T> JsonReadSession<T> readSession(Class<T> type, JsonReadOptions options) {
        return readSession(JsonType.of(type), options);
    }

    /**
     * 创建属于当前映射器的可变空对象；存储实现由 Provider 决定。
     */
    default JsonObject createObject() {
        return org.jetlinks.json.internal.JsonValues.object(this);
    }

    /**
     * 递归复制 JSON 容器为可变对象，保留显式 null，不修改源数据；POJO 请用 {@link #toTree(Object)}。
     */
    default JsonObject createObject(java.util.Map<String, ?> values) {
        JsonObject result = createObject();
        for (java.util.Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException("JSON object keys must be Strings");
            }
            result.put(name, entry.getValue());
        }
        return result;
    }

    /**
     * 创建属于当前映射器的可变空数组；存储实现由 Provider 决定。
     */
    default JsonArray createArray() {
        return org.jetlinks.json.internal.JsonValues.array(this);
    }

    /**
     * 递归复制 JSON 容器为可变数组，保留显式 null，不修改源数据；POJO 请用 {@link #toTree(Object)}。
     */
    default JsonArray createArray(java.util.Collection<?> values) {
        JsonArray result = createArray();
        values.forEach(result::add);
        return result;
    }

    /**
     * 以 O(1) 成本借用 Map，创建只读对象视图；不遍历或复制整棵树。
     * <p>源须为无环 JSON 数据：String 键 Map、List、null、String、Boolean 和结构工厂支持的精确 JDK 数字类型。
     * 其他 Collection、Java 数组、JsonValue 和 POJO 须先通过结构工厂或 {@link #toTree(Object)} 转换。
     * 不预先校验整图，不支持的叶值在访问时失败。
     * <p>外部修改可见；访问、绑定和复制期间须保持整个源稳定，视图不提供同步或并发快照。
     * 子视图始终引用获取时的子容器；祖先替换不会重定向已有子视图。
     *
     * @param values 调用方持有的非空 Map
     * @return 属于当前映射器的借用视图；包括嵌套容器在内的写入均抛出 {@link UnsupportedOperationException}；
     * {@code deepCopy()} 返回独立可变树，{@code toJavaValue()} 导出独立快照，均按需递归复制
     * @since 1.0.0
     * @see #wrap(java.util.List)
     */
    default JsonObject wrap(java.util.Map<String, ?> values) {
        return org.jetlinks.json.internal.JsonValues.borrowObject(values, this);
    }

    /**
     * 借用 List 为只读数组视图；源数据和所有权约束同 {@link #wrap(java.util.Map)}。
     * 创建时不访问元素，子容器按需包装；转换和序列化仍使用当前映射器配置。
     *
     * @param values 调用方持有的非空 List
     * @return 属于当前映射器的借用视图；需独立可变树时调用 {@code deepCopy()}
     * @since 1.0.0
     */
    default JsonArray wrap(java.util.List<?> values) {
        return org.jetlinks.json.internal.JsonValues.borrowArray(values, this);
    }

    /**
     * 将一个根值完整编码为 UTF-8 字节数组；结果整体驻留内存。
     */
    default byte[] writeBytes(Object value) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    /**
     * 将一个根值完整编码为 JSON 字符串；结果整体驻留内存。
     */
    default String write(Object value) {
        return new String(writeBytes(value), StandardCharsets.UTF_8);
    }

    /**
     * 从 UTF-8 字节数组读取唯一完整根值，拒绝尾随内容。
     */
    default <T> T read(byte[] input, JsonType<T> type) {
        return read(new java.io.ByteArrayInputStream(input), type);
    }

    /**
     * 从 JSON 字符串读取唯一完整根值，保留嵌套泛型；拒绝尾随内容。
     */
    default <T> T read(String input, JsonType<T> type) {
        return read(new java.io.StringReader(input), type);
    }

    /**
     * 从 JSON 字符串按类读取唯一完整根值，拒绝尾随内容。
     */
    default <T> T read(String input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 从 UTF-8 字节数组按类读取唯一完整根值，拒绝尾随内容。
     */
    default <T> T read(byte[] input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 解析 JSON 字符串为属于当前映射器的独立可变树，拒绝尾随内容。
     * 例如 {@code mapper.readTree("[1]").asArray().get(0).asInt()}。
     */
    default JsonValue readTree(String input) {
        // 原生扩展可能返回共享容器，绑定结果仍须复制以确保树独立。
        return org.jetlinks.json.internal.JsonValues.of(read(input, Object.class), this);
    }

    /**
     * 按当前 Provider 绑定配置将 Java 值转换为独立可变树；字符串作为值转换，不解析为 JSON。
     */
    default JsonValue toTree(Object value) {
        return org.jetlinks.json.internal.JsonValues.of(convert(value, JsonType.of(Object.class)), this);
    }
}
