package org.jetlinks.json;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 读取 JSON、生成 JSON，以及在 Java 对象和 JSON 树之间转换的入口。
 * 适合解析配置、处理接口返回值、导出数据；构建一次即可在多个线程共享。
 * <pre>{@code
 * JsonMapper mapper = Json.builder().provider("jackson2").build();
 * record Device(String id, boolean online) {}
 * Device device = mapper.read("{\"id\":\"dev-1\",\"online\":true}", Device.class);
 * String json = mapper.write(device); // {"id":"dev-1","online":true}
 * int first = mapper.readTree("[1,2]").asArray().get(0).asInt(); // 1
 * }</pre>
 * <p>已持有 Map/POJO 时用 {@link #convert(Object, Class)}；不确定字段结构时用 {@link #readTree(String)}。
 * 大数组逐条消费用 {@link #iterateArray(InputStream, Class)}；
 * 分块输入或定位嵌套数组用 {@link #readSession(JsonType, JsonReadOptions)}。
 * <p>整值 read/convert 会物化完整结果；返回 String/byte[] 的 write 会收集完整输出。
 * 单条超大值仍需足够内存；输出到流可避免收集完整输出字节。
 * 通过 Provider 完成解析和编码，不自动接管 HTTP 编解码器。
 * 映射器可共享，游标、迭代器和增量会话每次操作独立创建并关闭。
 *
 * @since 1.0.0
 * @see org.jetlinks.json.spi.JsonProvider
 */
public interface JsonMapper {

    /**
     * 查看当前映射器的通用设置，例如导入数据前检查单条记录的大小限制。
     * {@code int limit = mapper.options().maxValueBytes();}
     * 返回值不可变；修改策略用 {@link #withOptions(JsonOperationOptions)}，构建默认值用 {@link JsonMapperBuilder#options}。
     * <p>原生快照可能保留更丰富的属性包含、类型转换等规则，本配置不完整描述这些规则。
     */
    JsonOptions options();

    /**
     * 为一组读写操作单独设置选项，例如导出时保留 null、调试时格式化输出。
     * 返回新映射器，原映射器不变；未指定项继承，空覆盖返回当前实例。
     * <pre>{@code
     * JsonMapper exportMapper = mapper.withOptions(JsonOperationOptions.builder()
     *     .omitNulls(false)
     *     .pretty(true)
     *     .build());
     * String json = exportMapper.write(java.util.Collections.singletonMap("remark", null));
     * // json 包含 "remark": null；exportMapper 可以复用于后续导出。
     * }</pre>
     * <p>请复用返回值，避免每条数据都重新准备；更改 null 包含策略可能需要复制原生配置及隔离缓存。
     * 同步、树、迭代器和流式操作沿用这些设置，流式输出仍紧凑。
     * 连续派生时，通用项和同键属性后值覆盖前值；原生回调累积且可在准备时重放。
     *
     * @throws JsonException Provider 不支持、原生类型不匹配或配置冲突，类别为 CONFIGURATION；读写前失败
     * @since 1.0.0
     */
    default JsonMapper withOptions(JsonOperationOptions overrides) {
        if (java.util.Objects.requireNonNull(overrides, "overrides").isEmpty()) {
            return this;
        }
        throw new JsonException(JsonException.Kind.CONFIGURATION,
                "Provider does not support operation options");
    }

    /**
     * 将一个 Java 值编码为 UTF-8 JSON，适合写文件、响应体或压缩流。
     * <pre>{@code
     * try (var output = new java.io.ByteArrayOutputStream()) {
     *     mapper.write(java.util.Map.of("id", "dev-1"), output);
     *     byte[] json = output.toByteArray();
     * }
     * }</pre>
     * 本方法不关闭 output；真实文件或网络流应由创建它的一方关闭。
     */
    void write(Object value, OutputStream output);

    /**
     * 写出不带美化空白的 UTF-8 JSON，适合传输和存储，不更改映射器的 pretty 设置。
     * <pre>{@code
     * var output = new java.io.ByteArrayOutputStream();
     * mapper.writeCompact(java.util.List.of(1, 2), output);
     * String json = output.toString(java.nio.charset.StandardCharsets.UTF_8); // [1,2]
     * }</pre>
     * 仍使用当前绑定模块和属性策略；目标由调用方关闭。
     *
     * @since 1.0.0
     */
    void writeCompact(Object value, OutputStream output);

    /**
     * 将一个值写为 JSON 字符，适合已有 Writer、字符模板或自定义字符编码的场景。
     * {@code var text = new java.io.StringWriter(); mapper.write(java.util.List.of(1, 2), text);}
     * 字符到字节的编码由调用方的 Writer 决定；本方法不关闭 Writer。
     */
    void write(Object value, Writer output);

    /**
     * 从 UTF-8 输入流读取一个完整 JSON，适合文件或接口响应中的嵌套泛型数据。
     * <pre>{@code
     * var type = JsonType.listOf(JsonType.of(Integer.class));
     * try (var input = new java.io.ByteArrayInputStream("[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
     *     java.util.List<Integer> values = mapper.read(input, type);
     * }
     * }</pre>
     * 结果整体驻留内存；数组过大时使用 {@link #iterateArray(InputStream, JsonType)}。
     * 只接受一个完整根值，如 {@code 1 2} 或截断内容会失败；本方法不关闭输入流。
     */
    <T> T read(InputStream input, JsonType<T> type);

    /**
     * 从 UTF-8 流读取普通 DTO 或标量，例如 {@code Integer value = mapper.read(input, Integer.class);}。
     * List/Map 的泛型元素类型请使用 JsonType 重载；本方法不关闭输入流。
     *
     * @see #read(InputStream, JsonType)
     */
    default <T> T read(InputStream input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 从已有 Reader 读取完整 JSON，适合调用方已经完成字符解码的文件或文本。
     * {@code var values = mapper.read(new java.io.StringReader("[1,2]"), JsonType.listOf(JsonType.of(Integer.class)));}
     * 只接受一个完整根值，结果整体驻留内存；本方法不关闭 Reader。
     */
    <T> T read(Reader input, JsonType<T> type);

    /**
     * 从 Reader 读取一个 DTO 或标量，例如 {@code Integer value = mapper.read(new java.io.StringReader("1"), Integer.class);}。
     * 读取规则同 JsonType 重载；Reader 仍由调用方关闭。
     *
     * @see #read(Reader, JsonType)
     */
    default <T> T read(Reader input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 将已有 Map、List 或 POJO 转为另一种 Java 类型，适合把动态数据转成业务 DTO。
     * <pre>{@code
     * record Device(String id) {}
     * var type = JsonType.listOf(JsonType.of(Device.class));
     * java.util.List<Device> devices = mapper.convert(
     *     java.util.List.of(java.util.Map.of("id", "dev-1")), type);
     * }</pre>
     * 使用当前 Provider 的注解、Adapter 和类型转换规则，不保留源对象身份。
     * 字符串被当作字符串值；解析 JSON 文本请用 {@link #read(String, JsonType)}。
     */
    <T> T convert(Object value, JsonType<T> type);

    /**
     * 将 Map/POJO 转为指定类，适合无需泛型的 DTO 转换。
     * <pre>{@code
     * record Device(String id) {}
     * Device device = mapper.convert(java.util.Map.of("id", "dev-1"), Device.class);
     * String text = mapper.convert("[1]", String.class); // 仍是字符串 "[1]"，不会解析数组
     * }</pre>
     *
     * @see #convert(Object, JsonType)
     */
    default <T> T convert(Object value, Class<T> type) {
        return convert(value, JsonType.of(type));
    }

    /**
     * 创建按令牌读取的游标，适合只处理部分字段、跳过大容器或自行控制读取顺序。
     * 调用 {@code next()} 定位到值的首令牌后，再用 {@code readValue} 绑定或 {@code skipValue} 跳过。
     * 完整示例见 {@link JsonReader}；只需遍历根数组时优先用 {@link #iterateArray(InputStream, Class)}。
     * 关闭游标不关闭调用方输入流。
     */
    JsonReader reader(InputStream input);

    /**
     * 懒加载根数组的直接元素，使用当前 Provider 逐个绑定；不支持定位嵌套数组。
     * 适合从大文件逐条导入，避免先创建整个 List：
     * <pre>{@code
     * byte[] json = "[10,20,30]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
     * try (var input = new java.io.ByteArrayInputStream(json);
     *      var items = mapper.iterateArray(input, Integer.class)) {
     *     int total = 0;
     *     while (items.hasNext()) {
     *         total += items.next(); // 每次只绑定一个元素；此例总和为 60
     *     }
     * }
     * }</pre>
     * 泛型元素可传 {@code JsonType.listOf(JsonType.of(Integer.class))}，读取数组中的子数组。
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
     * 按 DTO 或标量类逐条消费根数组，例如 {@code mapper.iterateArray(input, Integer.class)}。
     * 用 try-with-resources 关闭迭代器；完整示例及限制见 JsonType 重载。
     *
     * @since 1.0.0
     * @see #iterateArray(InputStream, JsonType)
     */
    default <T> CloseableIterator<T> iterateArray(InputStream input, Class<T> type) {
        return iterateArray(input, JsonType.of(type));
    }

    /**
     * 创建手动组织对象/数组的写入器，适合一边查数据一边拼装嵌套导出文档。
     * 用 begin/end 配对容器，用 {@code value(row)} 直接写入每条 DTO，避免先收集完整 List。
     * 用法见 {@link JsonWriter}；关闭写入器不关闭调用方输出流。
     */
    JsonWriter writer(OutputStream output);

    /**
     * 创建可连续写值的紧凑写入器，不自动添加根值分隔符；分隔和目标流由调用方管理。
     * 每次操作或订阅独立创建；关闭时不补齐未闭合的容器。
     * 适合大批量数组导出，或已由上层管理分隔符的记录协议：
     * <pre>{@code
     * var output = new java.io.ByteArrayOutputStream();
     * try (JsonWriter writer = mapper.compactWriter(output)) {
     *     writer.beginObject();
     *     writer.name("items");
     *     writer.beginArray();
     *     writer.value(java.util.Map.of("id", "dev-1")); // 实际使用时可在循环中逐条写
     *     writer.endArray();
     *     writer.endObject();
     * }
     * // output 为 {"items":[{"id":"dev-1"}]}，无需创建承载全部结果的 List
     * }</pre>
     * 连续调用两次 value 不会自动产生合法数组或 NDJSON；须自行写容器或管理分隔符。
     *
     * @since 1.0.0
     */
    JsonWriter compactWriter(OutputStream output);

    /**
     * 将单篇 JSON 按对象开始、字段名、标量、容器结束等事件读取。
     * 适合转发巨大包装文档、统计令牌或在应用层重组结构，不先创建整个 Map/List。
     * {@code JsonReadSession<JsonEvent> session = mapper.eventSession();}
     * 会话的供给、读取和关闭方式见 {@link JsonReadSession}；每个标量仍须完整读取，解析限制仍生效。
     * 输入借用、取消和释放遵循增量读取会话契约。
     *
     * @since 1.0.0
     * @see JsonReadSession
     */
    JsonReadSession<JsonEvent> eventSession();

    /**
     * 创建手动驱动的分块读取会话，适合网络字节分段到达或读取包装对象中的嵌套数组。
     * <pre>{@code
     * var options = JsonReadOptions.selectElements(JsonPointer.of("/data/items"));
     * try (var session = mapper.readSession(Integer.class, options)) {
     *     byte[] bytes = "{\"data\":{\"items\":[1,2]}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
     *     session.feed(java.nio.ByteBuffer.wrap(bytes));
     *     session.endInput(); // 此例已供给全部输入；实际只在上游 EOF 时调用
     *     var first = session.read();  // VALUE(1)
     *     var second = session.read(); // VALUE(2)
     *     var end = session.read();    // END：剩余文档已校验
     * }
     * }</pre>
     * 仅解析已供给的字节，不自行读文件、等网络或创建线程。多段输入的完整循环见 {@link JsonReadSession}。
     * 类型和选项创建后固定；调用方负责串行供给、读取和关闭。
     */
    <T> JsonReadSession<T> readSession(JsonType<T> type, JsonReadOptions options);

    /**
     * 使用 DTO 或标量类创建分块读取会话，例如根数组逐条读取：
     * {@code mapper.readSession(Integer.class, JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))}。
     * 泛型目标、嵌套数组和资源管理示例见 JsonType 重载。
     *
     * @see #readSession(JsonType, JsonReadOptions)
     */
    default <T> JsonReadSession<T> readSession(Class<T> type, JsonReadOptions options) {
        return readSession(JsonType.of(type), options);
    }

    /**
     * 创建可修改的空 JSON 对象，适合动态拼装少量字段。
     * <pre>{@code
     * JsonObject value = mapper.createObject().put("id", "dev-1").put("online", true);
     * String json = mapper.write(value); // {"id":"dev-1","online":true}
     * }</pre>
     * 节点保留当前映射器，后续 {@code value.as(Device.class)} 使用相同绑定配置。
     */
    default JsonObject createObject() {
        return org.jetlinks.json.internal.JsonValues.object(this);
    }

    /**
     * 将已有 Map 复制成可修改的 JSON 对象，适合追加、删除字段而不修改原数据。
     * <pre>{@code
     * var source = java.util.Map.of("id", "dev-1");
     * JsonObject value = mapper.createObject(source).put("online", true);
     * // source 仍只有 id；value 含 id 和 online
     * }</pre>
     * 递归复制容器并保留显式 null；源须为结构工厂支持的无环 JSON 数据，POJO 请用 {@link #toTree(Object)}。
     * 只读访问且要避免复制时用 {@link #wrap(java.util.Map)}。
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
     * 创建可修改的空 JSON 数组，适合动态收集少量 JSON 值。
     * {@code JsonArray values = mapper.createArray().add(1).add(2);}
     * 节点保留当前映射器；超大结果逐条输出时用 {@link #writer(OutputStream)}。
     */
    default JsonArray createArray() {
        return org.jetlinks.json.internal.JsonValues.array(this);
    }

    /**
     * 将 Collection 复制为可修改数组，例如 {@code JsonArray values = mapper.createArray(java.util.List.of(1, 2));}。
     * 修改数组不影响源集合；递归复制嵌套容器并保留 null。
     * 元素须为结构工厂支持的 JSON 数据，POJO 集合请用 {@link #toTree(Object)}。
     */
    default JsonArray createArray(java.util.Collection<?> values) {
        JsonArray result = createArray();
        values.forEach(result::add);
        return result;
    }

    /**
     * 以 O(1) 成本借用 Map，创建只读对象视图；不遍历或复制整棵树。
     * 适合已有大量 Map/List 数据、只需要路径取值且希望省去复制的场景。
     * <pre>{@code
     * var source = new java.util.HashMap<String, Object>();
     * source.put("count", 1);
     * JsonObject view = mapper.wrap(source);
     * source.put("count", 2);
     * int count = view.get("count").asInt(); // 2，外部修改可见
     * JsonValue copy = view.deepCopy();     // 需要独立可修改树时再复制
     * // view.put("count", 3) 会抛 UnsupportedOperationException
     * }</pre>
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
     * {@code int first = mapper.wrap(java.util.List.of(10, 20)).get(0).asInt(); // 10}
     * 需要修改且不影响源 List 时，用 {@link #createArray(java.util.Collection)}。
     *
     * @param values 调用方持有的非空 List
     * @return 属于当前映射器的借用视图；需独立可变树时调用 {@code deepCopy()}
     * @since 1.0.0
     */
    default JsonArray wrap(java.util.List<?> values) {
        return org.jetlinks.json.internal.JsonValues.borrowArray(values, this);
    }

    /**
     * 将一个值编码为完整 UTF-8 字节数组，适合缓存、消息体或小文件。
     * {@code byte[] payload = mapper.writeBytes(java.util.Map.of("id", "dev-1"));}
     * 结果整体驻留内存；大批量导出可使用 {@link #write(Object, OutputStream)} 或令牌写入器。
     */
    default byte[] writeBytes(Object value) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        write(value, output);
        return output.toByteArray();
    }

    /**
     * 将一个值编码为 JSON 字符串，适合小对象日志、配置文本或数据库 JSON 字段。
     * {@code String json = mapper.write(java.util.List.of(1, 2)); // "[1,2]"}
     * 结果整体驻留内存；对已有 JSON 文本再次 write 会编码为带引号、转义后的字符串值。
     */
    default String write(Object value) {
        return new String(writeBytes(value), StandardCharsets.UTF_8);
    }

    /**
     * 从已收齐的 UTF-8 消息体读取泛型结果。
     * {@code var values = mapper.read("[1]".getBytes(java.nio.charset.StandardCharsets.UTF_8),
     * JsonType.listOf(JsonType.of(Integer.class)));}
     * 只接受一个完整根值；多段消息请用 readSession，避免为了本方法先聚合大文档。
     */
    default <T> T read(byte[] input, JsonType<T> type) {
        return read(new java.io.ByteArrayInputStream(input), type);
    }

    /**
     * 解析 JSON 文本并保留 List/Map 的元素类型，适合接口返回的集合或配置列表。
     * <pre>{@code
     * var type = new JsonType<java.util.Map<String, java.util.List<Integer>>>() {};
     * java.util.Map<String, java.util.List<Integer>> result = mapper.read("{\"values\":[1,2]}", type);
     * }</pre>
     * 只接受一个完整根值，拒绝尾随内容；结果整体驻留内存。
     */
    default <T> T read(String input, JsonType<T> type) {
        return read(new java.io.StringReader(input), type);
    }

    /**
     * 将 JSON 文本解析为 DTO 或标量，适合结构已知的小文档。
     * <pre>{@code
     * record Device(String id) {}
     * Device device = mapper.read("{\"id\":\"dev-1\"}", Device.class);
     * Integer count = mapper.read("3", Integer.class);
     * }</pre>
     * List/Map 中包含 DTO 时请用 JsonType 重载；空文本、截断文档和多个根值会失败。
     */
    default <T> T read(String input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 将完整 UTF-8 消息体解析为 DTO 或标量，例如
     * {@code Integer value = mapper.read("3".getBytes(java.nio.charset.StandardCharsets.UTF_8), Integer.class);}。
     * 泛型集合用 JsonType 重载；拒绝尾随内容。
     */
    default <T> T read(byte[] input, Class<T> type) {
        return read(input, JsonType.of(type));
    }

    /**
     * 将 JSON 文本解析为可修改树，适合字段结构不固定、按路径取值或增删字段。
     * <pre>{@code
     * JsonValue array = mapper.readTree("[1]");
     * int first = array.asArray().get(0).asInt(); // 1
     * JsonObject device = mapper.readTree("{\"id\":\"dev-1\"}").asObject();
     * device.put("online", true);
     * String json = mapper.write(device);
     * }</pre>
     * 树独立且保留当前映射器，输入须为唯一完整根值。整棵树驻留内存，不用于超大文档的逐条读取。
     */
    default JsonValue readTree(String input) {
        // 原生扩展可能返回共享容器，绑定结果仍须复制以确保树独立。
        return org.jetlinks.json.internal.JsonValues.of(read(input, Object.class), this);
    }

    /**
     * 将 Map、List 或 POJO 转为独立可修改树，适合序列化前补字段或检查对象的 JSON 表示。
     * <pre>{@code
     * record Device(String id) {}
     * JsonObject value = mapper.toTree(new Device("dev-1")).asObject();
     * value.put("online", true); // 不修改原 Device
     * JsonValue text = mapper.toTree("[1]"); // 字符串节点；解析数组须用 readTree("[1]")
     * }</pre>
     * 使用当前 Provider 的注解和 Adapter；转换整个对象并复制容器，树保留当前映射器。
     */
    default JsonValue toTree(Object value) {
        return org.jetlinks.json.internal.JsonValues.of(convert(value, JsonType.of(Object.class)), this);
    }
}
