# json-facade 使用手册

适用版本：`1.0.0-SNAPSHOT`，Java 17+。本文保留当前 API 的用法与选择依据；精确行为见 [公共契约](contracts.md)，模块边界见 [README](../README.md)。

## 目录

- [入口选择与安装](#start)
- [Provider、全局配置与 Spring](#provider)
- [通用配置](#options)
- [绑定与泛型](#binding)
- [树、路径与断言](#tree)
- [中立适配器与原生配置](#adapter)
- [同步游标](#cursor)
- [Reactor 读取与写出](#read-stream)
- [Netty 与 Spring DataBuffer](#netty)
- [结构事件](#events)
- [手动增量会话](#session)
- [资源所有权](#ownership)
- [性能实践与复现](#performance)
- [错误与 Provider 扩展](#errors)
- [API 索引](#reference)

<a id="start"></a>
## 入口选择与安装

门面用于应用主动发起的 JSON 操作。HTTP codec、WebClient、SSE、ORM 仍使用对应框架的官方集成。

| 需求 | 入口 | 物化单位 |
| --- | --- | --- |
| 配置、缓存、消息、DTO | JsonMapper read/write/convert | 一个完整值 |
| 嵌套泛型 | JsonType | 一个完整值 |
| 动态小文档 | createObject/createArray/readTree | 整棵树 |
| 同步根数组逐项读取 | iterateArray / CloseableIterator | 当前元素 |
| 同步结构读写 | JsonReader / JsonWriter | 当前 token 或绑定值 |
| 根数组、逐行记录 | ARRAY_ELEMENTS / NDJSON | 一个记录 |
| 包装文档中的数组 | Pointer selection | 一个选中元素 |
| 超大复杂文档 | readEvents/writeEvents | 一个 token 或 value 事件 |
| 自有事件循环 | readSession/eventSession | 一个结果 |
| 已有 ByteBuf / DataBuffer 流 | 对应传输适配器 | 输入租约、当前值与输出段 |

当前快照尚未发布到 Maven Central。先从源码安装到本地或发布到内部制品库：

```sh
mvn -B -ntp install
```

构建还需要 Python 3 执行聚合覆盖率检查。应用添加 API 与一个 Provider：

```xml
<properties>
    <json-facade.version>1.0.0-SNAPSHOT</json-facade.version>
</properties>
<dependencies>
    <dependency>
        <groupId>org.jetlinks</groupId>
        <artifactId>json-facade-api</artifactId>
        <version>${json-facade.version}</version>
    </dependency>
    <dependency>
        <groupId>org.jetlinks</groupId>
        <artifactId>json-facade-jackson2</artifactId>
        <version>${json-facade.version}</version>
    </dependency>
</dependencies>
```

Jackson 3 改用 `json-facade-jackson3`；响应式操作加 `json-facade-reactor`；Netty 加 `json-facade-netty`。Spring 配置桥接加 `json-facade-spring`，其 DataBuffer 入口还需显式加 Reactor 模块。

当前依赖基线为 Jackson 2 `2.22.3`、Jackson 3 `3.0.3`、Reactor `3.7.18`、Netty `4.1.138.Final`。覆盖版本后应验证真实引擎兼容性。TCK 与 coverage 模块不属于应用运行依赖。

以下完整示例可保存为 QuickStart.java：

```java
import java.util.List;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonType;

public class QuickStart {
    public record Item(String id, int count) { }

    public static void main(String[] args) {
        JsonMapper mapper = Json.builder().provider("jackson2").build();
        String json = mapper.write(new Item("device-1", 3));
        Item item = mapper.read(json, Item.class);
        List<Item> items = mapper.read("[" + json + "]",
            JsonType.listOf(JsonType.of(Item.class)));
        System.out.println(item.id() + ": " + items.size());
    }
}
```

下文片段沿用 Item、已构建的 mapper，以及 `JsonStreamMapper streams = JsonStreamMapper.of(mapper)`。按需导入 `org.jetlinks.json`、`org.jetlinks.json.reactor`、JDK 与 Reactor 类型；声明为方法/类型的片段放在类体中。

<a id="provider"></a>
## Provider 与实例管理

```java
JsonMapper mapper = Json.builder().provider("jackson2").build();
JsonMapper explicit = Json.builder()
    .provider(new org.jetlinks.json.jackson2.Jackson2Provider()).build();
JsonMapper isolated = Json.builder().classLoader(applicationClassLoader)
    .provider("jackson3").build();
```

直接传 Provider 绕过 ServiceLoader；宿主提供 applicationClassLoader 时可限定发现范围。最后一次 provider 设置生效。未指定时必须恰有一个候选；无匹配、歧义或重复 id 都会失败，解析错误不会触发换引擎重试。

构建后的 mapper 可跨线程共享，按操作创建 reader/writer/session。不同业务规则使用不同 mapper；不要每条消息构建一次。

### 默认与命名实例

应用在首次访问默认实例前注册，配置仍由普通 Builder 完成：

```java
JsonOptions strict = JsonOptions.DEFAULT
    .withFailUnknownProperties(true).withFailDuplicateKeys(true);
Json.registerGlobal(Json.builder().provider("jackson2").options(strict).build());
Json.registerGlobal("partner-input",
    Json.builder().provider("jackson3").options(strict).build());

String json = Json.global().write(java.util.Map.of("status", "ready"));
JsonMapper partner = Json.global("partner-input");
```

`Json.global()` 与 `defaultMapper()` 返回同一默认 mapper；未注册时首次访问惰性发现一个 Provider。名称区分大小写、不可为空白或含首尾空白。命名位置独立于无参默认位置；未知名称和重复注册失败，不会回退或替换。

全局引用与 API ClassLoader 同寿命，不提供 reset/unregister/close。只注册启动时已知的有限配置名；不要用请求、设备或动态租户 ID。插件卸载、多应用隔离和可重复测试优先注入宿主持有的普通实例。

容器可用 `registerGlobalFactory(factory)` 提前保留默认位置，首次访问再创建。失败可重试同一工厂，保留位置不能被替换；工厂不得递归访问默认实例。未保留位置的默认发现失败不会发布半成品，之后仍可显式注册。

<a id="spring"></a>
### Spring 默认配置

Spring 模块支持 Spring 6 / Boot 3 的 Jackson 2 ObjectMapper。Boot 自动适配唯一或 primary 原生 Bean；宿主 JsonMapper Bean 优先。普通 Spring 显式导入 `org.jetlinks.json.spring.JsonFacadeConfiguration`。没有 Boot 4 / Jackson 3 自动桥接。

适配使用原生 copy，保留模块、mixin、日期、命名、coercion 和完整 inclusion/filter 策略，不修改源 Bean，也不跟随后续变更。副本执行门面的单根读取、调用方 IO 所有权和不自动补全容器契约。原生自定义 handler 按 Jackson copy 规则共享，须线程安全。

| 属性 | 默认 | 作用 |
| --- | --- | --- |
| json.facade.enabled | true | Boot 自动配置开关 |
| json.facade.global-enabled | true | 注册默认全局；关闭后仍可注入 Bean |
| json.facade.max-value-bytes | 8388608 | 适配 mapper 的增量记录预算；宿主自定义 mapper 自行配置 |

普通业务 Bean 初始化时可使用全局门面；原生 mapper、customizer 或宿主门面构造中不能反向依赖该全局，以免循环初始化。更早的 BeanFactoryPostProcessor 不能依赖这项注册。默认位置已占用、原生候选缺失/歧义会明确失败，全局门面须为 singleton。

关闭 ApplicationContext 不重置全局。重复上下文应关闭 global-enabled 并注入各自实例；原生 handler 也可能持有容器引用。失败启动会释放 bootstrap 的 BeanFactory 引用，但不取消全局保留位置。普通 Spring 配置入口无需 Reactor/Netty。非 Spring 宿主可用 `Jackson2Provider.adapt(nativeMapper[, maxValueBytes])` 创建同样的配置快照；源须为支持 copy 的 JSON mapper。

<a id="options"></a>
## 通用配置与预算

JsonOptions 是不可变 record，九个字段均有具名 withX 副本方法，保留其他配置并复用构造器校验。

| 字段 | 默认值 | 含义 |
| --- | --- | --- |
| losslessNumbers | true | 无类型小数保留 BigDecimal，大整数可用 BigInteger |
| failUnknownProperties | false | DTO 未知字段拒绝 |
| failDuplicateKeys | false | 原生解析器检测重复字段 |
| omitNulls | false | 原生绑定的 null 属性策略 |
| pretty | false | 同步输出美化；流输出始终 compact |
| maxDepth | 256 | 原生嵌套深度 |
| maxStringLength | 8 MiB | 原生字符串 token 长度 |
| maxNumberLength | 1000 | 原生数字 token 长度 |
| maxValueBytes | 8 MiB | 增量物化记录/流输出当前值字节预算 |

```java
JsonOptions options = JsonOptions.DEFAULT.withFailDuplicateKeys(true);
JsonMapper strict = Json.builder().provider("jackson2").options(options).build();
JsonReadOptions selected = JsonReadOptions.selectElements(JsonPointer.of("/data/items"))
    .withNullPolicy(JsonReadOptions.NullPolicy.SKIP);
JsonWriteOptions output = JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)
    .withChunkSize(16 * 1024);
```

流操作有效值预算取 mapper 与操作 options 的较小值；两边默认独立为 8 MiB，仅增大 mapper 不会改变操作默认限制。同步整值 read/write/convert、整流累计字节、输入 chunk、并发和超时由宿主另行限制。重复键检测还会保存每个未闭合对象的已见字段名。

<a id="binding"></a>
## 对象绑定、泛型与转换

```java
String text = mapper.write(new Item("device-1", 3));
byte[] utf8 = mapper.writeBytes(new Item("device-1", 3));
Item item = mapper.read(utf8, Item.class);
Item converted = mapper.convert(java.util.Map.of("id", "device-1", "count", 3), Item.class);

JsonType<java.util.List<java.util.Map<String, Item>>> nested =
    new JsonType<java.util.List<java.util.Map<String, Item>>>() { };
var result = mapper.read("[{\"first\":{\"id\":\"device-1\",\"count\":3}}]", nested);
```

普通类型直接传 Class：String/byte[]/InputStream/Reader 读取、convert、游标 readValue、readSession 和各传输适配器的 typed 读取都有对应重载。它们一次委托到 JsonType；嵌套泛型继续用匿名捕获、listOf/mapOf 或已有 JDK Type。不要依赖未绑定的类型变量恢复运行时 T。

同步 read 要求完整单根，拒绝尾部非空白；空输入不等于 JSON null。字节 API 使用 UTF-8，字符 IO 保留字符语义；调用方管理传入 IO，底层同步 IO 仍可能阻塞。

Jackson 3 直接使用配置化原生 byte[] 输入/输出；String 输出从 UTF-8 字节解码，与 Writer 输出的 Unicode 转义表示可能不同。Jackson 2 保留流路径以保持 IO decoration 和原始 IO 异常。convert 使用 JSON 绑定语义，不保留对象身份，也不保证零分配。

日期、Optional、枚举、二进制、Map 键、多态和注解遵循 Provider 配置。明确绑定 double 仍使用 double 精度。写入接受 Object，没有 write(value, JsonType)；依赖声明泛型或多态标签的根集合应使用真实样本验证，必要时用 DTO 包装。

<a id="tree"></a>
## 树、路径访问与断言

```java
JsonObject root = mapper.createObject(java.util.Map.of("version", 1));
root.put("items", java.util.List.of(java.util.Map.of("id", "device-1", "count", 3)));
root.put("note", null);
root.getArray("items").add(java.util.Map.of("id", "device-2", "count", 4));

JsonPointer idPath = JsonPointer.of("/items/0/id");
String id = root.at(idPath).asString();
boolean explicitNull = root.get("note").isNull();
boolean absent = root.get("unknown").isMissing();
int fallback = root.get("unknown").getArray("items").get(0).asInt(10);
JsonObject independent = root.deepCopy();
Object snapshot = root.toJavaValue();
```

createObject/createArray、put/add/set 和导出/复制会隔离嵌套容器；插入原容器后再修改原容器，不影响目的树。输入须无环且对象键为 String。JsonValue.of(existingNode) 保留节点身份，隔离请用 deepCopy。

已有 Map/List 只需读取时，可借用原容器，省去递归复制：

```java
java.util.Map<String, Object> source = new java.util.HashMap<>();
source.put("items", java.util.List.of(java.util.Map.of("count", 3)));
JsonObject view = mapper.wrap(source);
int count = view.at(JsonPointer.of("/items/0/count")).asInt();
JsonArray listView = mapper.wrap(java.util.List.of(1, 2, 3));
JsonObject editable = view.deepCopy();
```

wrap 创建只分配根视图，不遍历数据；子节点按访问包装，转换与序列化复用所属 Provider。根及嵌套容器的 put/remove/add/set 均抛 UnsupportedOperationException；缺失/null 的写入仍按原有缺失/null 契约失败。外部修改源可被观察；借用不是冻结或并发快照，访问、绑定及复制期间须保持整个源稳定。已取得的子视图仍指向原子容器，祖先替换后须重新定位。

借用源须为无环的 String 键 Map、List、null、String、Boolean 或下文列出的精确 JDK 数字。wrap 不扫描校验整图；访问到不支持的叶值时失败。嵌套 Collection（非 List）、Java 数组、JsonValue 和 POJO 请先用 createXX/toTree 转换。toJavaValue 仍递归导出独立快照，deepCopy 返回保留 mapper 配置的独立可变树；需要对外隔离时显式选择它们。

结构工厂接受 JSON 标量、Map、Collection 与 Java 数组，不绑定任意 POJO；使用 mapper.toTree(bean) 应用命名、日期和适配器。结构 byte[] 是数字数组，原生绑定通常为 Base64。结构数字只接受精确的 JDK Byte/Short/Integer/Long/Float/Double/BigInteger/BigDecimal，拒绝可变 Number、自定义 Number 和大数子类；这些对象可通过 toTree 绑定。

### 访问、转换与位置

- missing/null 都是非 Java null 视图；使用 isMissing/isNull，不比较单例身份。缺失导出失败。
- getObject/getArray 支持名称和索引，缺失/null 可链式访问且不自动创建数据；实际形状不符会失败。asObject/asArray 严格要求实际容器。
- asInt/asLong 等基本类型在缺失/null 时返回零/false；asString/asInteger 等引用类型返回 null，也可显式指定默认值。实际值的转换失败不会用默认值吞掉。
- present 值按所属 mapper 转换。mapper 创建、绑定的树和 deepCopy 保留配置；静态 JsonValue.object/array/of 无配置，实际值转换需显式 mapper.convert。
- JsonPointer 为 RFC 6901，不是 JSONPath；空字符串表示根，~1/~0 转义斜线/波浪号，数组索引用规范十进制，不能用 01 或 - 读取。
- 固定路径可复用编译 Pointer；默认树融合定位只创建最终视图，遍历为 O(d)。缓存父视图后祖先被替换，旧视图仍指向原容器，需重新从根定位。
- 路径保存段信息，不保留祖先树。deepCopy 从根开始，插入采用目的路径。节点不承诺结构 equals；未知字段遍历可导出 Map 快照，大文档改用游标/事件。

```java
Integer port = root.get("config").get("port")
    .requireNonNull(failure ->
        new IllegalArgumentException(failure.displayPath() + " 不能为空"))
    .asInteger();
root.get("items").require(value -> value.size() > 0);
```

requirePresent 允许 JSON null，requireNonNull 同时拒绝缺失/null，require 接受谓词。成功返回当前节点；异常工厂只在失败时调用，返回的异常实例和谓词异常原样传播。默认失败为 VALIDATION。

JsonFailure 提供 kind/reason/message/pointer/displayPath/cause；reason 区分 MISSING、NULL、TYPE_MISMATCH、CONVERSION、ASSERTION。未知位置为 null，空 Pointer 为根。转换错误会拼接访问路径与原生结构化绑定路径，例如 /items/0/count 和 items[0].count。

<a id="adapter"></a>
## 中立适配器与原生配置

中立模块按精确 Class 注册，不自动覆盖子类，也不按泛型 Type 注册。以下类型声明放在类体内：

```java
public record DeviceId(String value) { }

public static final class DeviceIdModule implements org.jetlinks.json.spi.JsonModule {
    public String id() { return "device-id"; }

    public void configure(org.jetlinks.json.spi.JsonModuleContext context) {
        context.add(DeviceId.class, new org.jetlinks.json.spi.JsonAdapter<DeviceId>() {
            public void write(DeviceId value, JsonWriter writer) {
                writer.string(value.value());
            }
            public DeviceId read(JsonReader reader) {
                return new DeviceId(reader.readValue(String.class));
            }
        });
    }
}
```

```java
JsonMapper customized = Json.builder().provider("jackson2")
    .module(new DeviceIdModule()).build();
DeviceId id = customized.read("\"device-1\"", DeviceId.class);
```

模块 id 和同一 mapper 的 Class 适配器不得重复。适配器须线程安全；游标是借用对象，不关闭、不保留，读取/写入恰好一个完整值。Java null 不调用适配器。writer.value 使用正常绑定，不要把对象重新交给其自身适配器；直接 token 方法绕过对应标量适配器。

<a id="native"></a>
### 原生能力复用

```java
JsonMapper snakeCase = Json.builder().provider("jackson2")
    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
        builder -> builder.propertyNamingStrategy(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE))
    .build();
```

Jackson 3 使用 tools.jackson.databind.json.JsonMapper.Builder 和对应命名策略类。mixin、原生模块、日期规则直接在此配置。Jackson 2 使用 java.time/Optional 时添加匹配版本的 jackson-datatype-jsr310/jdk8，并在回调注册 JavaTimeModule/Jdk8Module；Jackson 3 已集成常见 JDK 类型支持，迁移仍需核对日期精度、时区和空值。

每次 build 使用新原生 builder，先检查全部回调类型再顺序执行。类型不符、回调失败或与 JsonOptions 管理策略冲突都会失败；不要依赖回调覆盖共享策略。不能保留 builder、修改已发布 mapper 或在回调执行 IO。没有共享可变原生 mapper getter，专有原生操作由宿主管理其边界。

<a id="cursor"></a>
## 同步文件与游标

普通文件由调用方关闭；门面释放自己的解析/写入状态：

```java
static Item readFile(JsonMapper mapper, java.nio.file.Path path) throws java.io.IOException {
    try (var input = java.nio.file.Files.newInputStream(path)) {
        return mapper.read(input, Item.class);
    }
}
```

根数组可直接使用可关闭迭代器，不收集整个文件：

```java
static void consumeArray(JsonMapper mapper, java.io.InputStream input,
                         java.util.function.Consumer<Item> consume) {
    try (CloseableIterator<Item> iterator = mapper.iterateArray(input, Item.class)) {
        while (iterator.hasNext()) {
            consume.accept(iterator.next());
        }
    }
}
```

泛型元素可传 JsonType，例如 iterateArray(input, new JsonType<List<Item>>() { })。
创建迭代器不读取输入；hasNext 只推进到下个元素 token，不提前绑定，反复调用不重复推进；
next 返回一个完整元素，也可能是 Java null。耗尽后 next 抛 NoSuchElementException。
完整迭代校验数组闭合与尾部 EOF，耗尽/解析失败会释放解析器；提前 close 只校验已读前缀。
始终使用 try-with-resources，消费者异常时同样会清理；传入的 input 仍由调用方关闭。
这是同步、可能阻塞的根数组入口，不支持 NDJSON/selection，继承原生 token/depth 限制，
不套用增量 session 的 maxValueBytes；单个元素仍须有界。

手动 token 处理继续用 mapper.reader(input)：readValue/skipValue 消费当前完整值，下次 next
返回后继，next 返回 null 才是 EOF，text 读取字段名或标量文本。写出可组合结构与绑定：

```java
static void writeArray(JsonMapper mapper, java.io.OutputStream output, Iterable<Item> items) {
    try (JsonWriter writer = mapper.compactWriter(output)) {
        writer.beginArray();
        for (Item item : items) {
            writer.value(item);
        }
        writer.endArray();
        writer.flush();
    }
}
```

writer 遵循 pretty，compactWriter/writeCompact 只为本次操作使用 compact。number("1.2300e+10") 校验完整数字语法，不经过 double，也不是 raw 注入。关闭不补残缺括号；多个根值的分隔由调用方负责。

<a id="read-stream"></a>
## Reactor 流式读取

输入 chunks 是 Publisher<byte[]>，可以切在任意 UTF-8/token 位置，不代表记录边界：

```java
Flux<Item> items = streams.readBytes(chunks, Item.class,
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
Flux<Item> selected = streams.readBytes(chunks, Item.class,
    JsonReadOptions.selectElements(JsonPointer.of("/data/items")));
```

| 模式 | 行为 | 空输入 |
| --- | --- | --- |
| SINGLE_VALUE | 一个完整根值 | 失败 |
| ARRAY_ELEMENTS | 根数组直接元素 | 失败；空数组正常完成 |
| NDJSON | 每个非空行一个值，LF/CRLF，末尾 LF 可省 | 零条记录 |
| ROOT_VALUES | JSON 空白分隔的根值，不展开数组 | 零条记录 |

NDJSON 不接受裸 CR、跨行记录或同行多个根。selection 仅用于 SINGLE_VALUE；selectValue 绑定完整目标，selectElements 发出目标数组直接元素。路径缺失、形状错误或重复出现都会失败，即使重复键检测关闭。无关子树仍校验语法和 token/depth，但不物化。take/取消只验证消费前缀，整篇校验须消费到完成。

Reactor 不允许 Java null，默认 FAIL；可用 withNullPolicy(SKIP)，或读为 JsonValue 保留 JSON null。readBuffers 接受 heap/direct/read-only/slice ByteBuffer，在适配器收到输入时立即复制，保留源 cursor。

业务保存可用 `items.concatMap(save, 0).then()` 串行执行；按宿主容量设置有限并发/批次。返回 Publisher 给宿主订阅，不在 JSON 链内 block/subscribe 或收集整个流。解析失败不回滚已完成的保存，事务、幂等和重试由业务负责；损坏 session 不能用 onErrorContinue 恢复。

<a id="write-stream"></a>
## Reactor 分块写出

```java
JsonWriteOptions output = JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)
    .withChunkSize(16 * 1024);
Flux<byte[]> bytes = streams.writeBytes(items, output);
Flux<java.nio.ByteBuffer> buffers = streams.writeBuffers(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON));
```

| 格式 | 行为 | 空源 |
| --- | --- | --- |
| SINGLE_VALUE | 恰好一个完整值，多条失败 | 失败 |
| ARRAY | 数组包围所有记录 | [] |
| NDJSON / ROOT_VALUES | 每条 compact 值后接 LF | 零字节 |

默认 chunkSize=8192，maxValueBytes=8 MiB；withChunkSize/withMaxValueBytes 保留其他设置。分段可能切开字符或字段，不等于记录；分隔符预算与值分开核算。

每订阅复用原生 compact writer，每记录使用新绑定上下文，避免 object ID 跨记录积累。一个有限 POJO 会同步编码为自有分段后按需求交付，不能在任意字节处暂停原生 serializer。大型聚合使用结构事件。失败/取消不会补成功尾括号，已发字节无法撤回。

<a id="netty"></a>
## Netty ByteBuf

使用宿主 allocator；以下片段位于已有 inbound/outbound 的 Reactor Netty 处理器中：

```java
var netty = org.jetlinks.json.netty.NettyJsonStreamMapper.of(mapper, outbound.alloc());
Flux<Item> items = netty.read(inbound.receive(), Item.class,
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
return outbound.send(netty.write(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)));
```

read/readEvents 在输入 onNext 中 retain 一个引用，receive 仍释放自己的引用。若源把一个引用直接移交订阅者，用 readOwned/readOwnedEvents；不要重复释放或 retain。上游负责未交付缓冲和自己的队列，租约结束前字节与组件须稳定。

heap/direct、slice、只读和 composite 输入保持 indices，按 NIO 组件推进而不合并；不能提供 NIO view 的 ByteBuf 明确失败。错误/取消在活动 native 回调返回、解析器清理后释放引用。

write/writeEvents 发出时转移一个引用；send 接管接受的段，其他消费者使用后 release。下游自加异步队列应清理 discard，例如：

```java
var queued = netty.write(items, output)
    .publishOn(outputScheduler, 8)
    .doOnDiscard(io.netty.buffer.ByteBuf.class, io.netty.buffer.ByteBuf::release);
return outbound.send(queued);
```

解析之前加队列时须先正确 retain，并将那个引用交给 readOwned；通常在解析后组合异步业务更直接。适配器只释放未发布输出。原生编码缓冲到 ByteBuf 仍有复制，不保证端到端零复制。TCP read/ByteBuf/输出段都不是 JSON 文档边界。

<a id="databuffer"></a>
## Spring DataBuffer

使用 Spring 模块与显式 Reactor 依赖；以下片段位于已有 request/response 的响应式处理器中：

```java
var dataBuffers = org.jetlinks.json.spring.DataBufferJsonStreamMapper.of(
    mapper, response.bufferFactory());
Flux<Item> items = dataBuffers.read(request.getBody(), Item.class,
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
return response.writeWith(dataBuffers.write(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)));
```

可用 DefaultDataBufferFactory 或宿主的池化工厂，不改变 HTTP codec。read/readEvents 接管一个已交付输入所有权，不额外 retain。输入位置保持不变，组件迭代器在 Provider 借用期间保持打开，清理时先关闭迭代器再释放输入，即使关闭失败也释放。

write/writeEvents 发出的段由下游接管，response.writeWith 负责接受后的释放；适配器释放未发布段。下游自加队列应配置 doOnDiscard(DataBuffer.class, DataBufferUtils::release)，上游负责尚未交付的缓冲。数组、selection 和事件复用相同 options，输入/输出分段不代表整篇文档。

<a id="events"></a>
## 复杂文档的结构事件

在元数据之间流式输出数组，汇总状态按订阅建立：

```java
static Flux<byte[]> exportDocument(JsonStreamMapper streams, Flux<Item> items) {
    return Flux.defer(() -> {
        var count = new java.util.concurrent.atomic.AtomicLong();
        Flux<JsonEvent> events = Flux.concat(
            Flux.just(JsonEvent.START_OBJECT,
                JsonEvent.name("meta"), JsonEvent.value(java.util.Map.of("version", 1)),
                JsonEvent.name("items"), JsonEvent.START_ARRAY),
            items.doOnNext(item -> count.incrementAndGet()).map(JsonEvent::value),
            Flux.defer(() -> Flux.just(JsonEvent.END_ARRAY,
                JsonEvent.name("summary"), JsonEvent.value(java.util.Map.of("count", count.get())),
                JsonEvent.END_OBJECT)));
        return streams.writeEvents(events,
            JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE));
    });
}
```

| 事件 | 用途 |
| --- | --- |
| START_OBJECT/END_OBJECT、START_ARRAY/END_ARRAY | 不可变容器常量 |
| name / string / number | 字段名、文本、完整数字文本 |
| TRUE/FALSE/NULL | 直接标量常量 |
| value(Object) | 配置化绑定一个完整值，可为 Java null |
| token(JsonToken, String) | 按 token 类型构造 |

事件输出仅支持 SINGLE_VALUE，校验容器、字段/值顺序和唯一根。直接标量绕过 Java 标量适配器，value 使用正常绑定和当前值预算。number 校验语法并保持精度，无 raw 注入。

`streams.writeEvents(streams.readEvents(chunks), options)` 可转发整篇结构，ByteBuffer 对应 readBufferEvents/writeEventBuffers；Netty/DataBuffer 使用各自 readEvents/writeEvents。事件不会收集包围容器，但不会拆开单个巨大 POJO/string/number。转发不保留源空白或转义，原生 tokenizer 也会把整数 -0 规范为 0；签名依赖原文时保留原始字节。

<a id="session"></a>
## 手动增量解析会话

不引入 Reactor 时由宿主驱动 session，下面声明放在类体中：

```java
static <T> void pump(JsonMapper mapper, java.util.Iterator<java.nio.ByteBuffer> chunks,
                     JsonType<T> type, JsonReadOptions options,
                     java.util.function.Consumer<T> consume) {
    try (JsonReadSession<T> session = mapper.readSession(type, options)) {
        boolean ended = false;
        while (true) {
            var result = session.read();
            switch (result.state()) {
                case VALUE -> consume.accept(result.value());
                case END -> { return; }
                case NEED_INPUT -> {
                    if (ended || !session.needsInput()) {
                        throw new IllegalStateException("Unexpected input request");
                    }
                    if (chunks.hasNext()) {
                        session.feed(chunks.next());
                    } else {
                        session.endInput();
                        ended = true;
                    }
                }
            }
        }
    }
}
```

只在 needsInput 时 feed；借用 remaining 区域且不改 cursor，直到下一次 needsInput 或 close 前保持存储有效、不可修改。真实 EOF 调用一次 endInput 后继续读到 END，VALUE(null) 不等于 EOF。异步事件循环在 NEED_INPUT 时等待来源，不忙轮询。

eventSession 使用同样协议返回 JsonEvent。read(cancelled) 可传线程安全非阻塞取消谓词，取消可能直接 END，之后仍须 close。read(receiver, cancelled) 将一个状态/值同步交给自选返回类型的 Receiver，成功时恰好调用一次，允许 VALUE(null)。当前 Providers 直接交付以免每条分配 Result；Receiver 不得阻塞或重入，异常原样传播，失败后关闭操作。

<a id="ownership"></a>
## 资源、线程与所有权

| 对象 | 责任 |
| --- | --- |
| Mapper / stream mapper | 构建后共享，无每次操作状态 |
| Builder / 树 / cursor / session | 不并发修改；cursor/session 按操作关闭 |
| 调用方同步 IO | 调用方关闭，底层可能阻塞 |
| CloseableIterator | 按操作使用；耗尽/解析失败自动关闭解析器，提前停止须 close，不关闭输入 |
| 输入 byte[] | 发布后保持不可变，不复用 |
| 输入 ByteBuffer | 适配器 onNext 内复制；之前的异步队列由宿主保证有效 |
| 手动 session 的 ByteBuffer | 借用至 needsInput 或 close |
| 输出 byte[] / ByteBuffer | 独立存储转移下游；ByteBuffer 只读、cursor 独立，无 release |
| Netty / DataBuffer | 按上文的显式借用或转移契约释放 |

每次订阅独立创建操作状态，保留 Reactor Context 和上游异常身份，无内部 subscribe/block、强制调度或整流聚合。取消向上传播；活动 native/自定义回调返回后再清理资源。只读 view 不能防止其他持有者改写字节。

关闭包装器会断开原生上下文与借用存储引用。库不代替宿主关闭真实连接、回滚副作用或释放宿主额外持有的引用；写入完成前也不得并发修改待写值。

<a id="performance"></a>
## 大文档与性能实践

| 预算 | 控制方式 |
| --- | --- |
| 累计字节、超时 | 宿主网络/文件策略 |
| 输入 chunk | 上游读取块大小 |
| token / 深度 | JsonOptions 原生限制 |
| 当前记录 / 绑定值 | mapper 与操作 maxValueBytes 较小值 |
| 输出段 | chunkSize 加当前值预算 |
| 业务缓存 / 并发 | 有限并发、批次、预取、队列 |
| 重复键字段集合 | 避免单个无限字段对象 |

共享 mapper/JsonType，DTO 直接绑定；根数组和包装数组用记录/selection，大结构用游标/事件。避免 write→String→getBytes、readTree→export→convert 和反复导出整树。已有不可变 byte[] 不必为了池化再包装为 ByteBuf；选择源已有的传输能力。不要用 cache/replay/collectList 把整流重新留在堆中。

当前热点仍包括短记录响应式写入、token 交付、字符表示转换和树隔离。Native 对照若缺少背压/取消/预算，不可将全部差值解释为可移除成本。Netty 避免整块输入复制和中间输出数组，但小记录耗时未必受益，按真实负载选择 allocator 和段大小。

### 验证与复现

Java 17/21 各 249 项测试与编译、Checkstyle、SpotBugs、覆盖率门禁通过。同步迭代器在两套 Provider 上通过约 64 MiB 数组/32 MiB 堆及关闭输入释放验证。已有至少 1 GiB 聚合文档的 128 MiB 堆验证；池化适配还限制直接内存并检查结束时零 pinned bytes。这证明对应有界场景，不保证任意巨大单值、并发或 RSS 恒定。完整范围见 [验证契约](contracts.md#implementation-and-validation)。

完成源码构建后按需运行；默认大文档矩阵耗时较长：

```sh
python3 scripts/run-tree-access-stress.py
python3 scripts/run-streaming-stress.py --mib 16 --heaps 128 --chunks 64 65536
python3 scripts/run-streaming-stress.py
python3 scripts/run-resource-release-stress.py
python3 scripts/run-netty-stress.py
python3 scripts/run-netty-stress.py --release-only
python3 scripts/run-databuffer-stress.py
python3 scripts/run-databuffer-stress.py --release-only
python3 scripts/run-streaming-benchmarks.py
python3 scripts/run-streaming-benchmarks.py --tree
python3 scripts/run-streaming-benchmarks.py --iterator
python3 scripts/run-streaming-benchmarks.py --provider-read
python3 scripts/run-streaming-benchmarks.py --published-write
python3 scripts/run-streaming-benchmarks.py --netty
```

压力测试按需生成并独立校验结构；JSON_STRESS_JAVA 指定 JVM。JMH 参数可透传，比较时固定配置、输入、所有权与消耗方式，记录 fork、预热、堆和 GC profiler。累计分配、存活堆、池化 reserved/pinned 与 RSS 分别观察，微基准不代替生产网络测试。

<a id="errors"></a>
## 错误处理

| JsonException.kind | 常见来源 |
| --- | --- |
| PROVIDER | 发现缺失/歧义/重复 id |
| CONFIGURATION | 配置冲突、重复注册、未知名称、初始化重入 |
| SYNTAX | 无效 JSON、残缺输入、分帧错误 |
| BINDING | 类型转换或适配失败 |
| LIMIT | token、深度、当前值预算 |
| IO | 同步 IO 失败 |
| VALIDATION | 树默认断言失败 |

JsonException 保留原始 cause 并可提供结构化位置；当前没有独立中立 JsonLocation。API 参数/状态误用也可能抛 JDK 异常，上游响应式错误原样传播。失败 session 应关闭并从源头建立新操作，不能继续解析。

NDJSON 暂无自动跳坏行模式；容错业务须先建立独立行边界和预算，再逐行独立解析。raw JSON 注入、CBOR/Smile/XML 未提供，压缩由宿主传输层处理。

<a id="migration"></a>
## Provider 扩展与迁移

JsonProvider 仅有 id/create，实现必须遵守同步、树、游标与非阻塞增量/事件的全部契约。原生引擎负责 tokenizer、绑定与原生生命周期，公共层负责分帧/选择/预算，Reactor 协调 demand/cancel。不要复制 Provider 已提供的解析器、绑定逻辑或原生算法。

Provider 可以注册 META-INF/services/org.jetlinks.json.spi.JsonProvider，或由应用显式传工厂。测试依赖 json-facade-tck 并继承 JsonMapperContract，实现 mapper(JsonOptions, JsonModule...)；另测原生特有配置。当前只有 Jackson 2/3 实现，不将同步引擎的整流聚合伪装为非阻塞解析。

迁移保留中立 DTO/JsonType/JsonValue/模块，逐项验证原生注解、mixin、日期、枚举、Map 键、多态、null、精度与异常。框架 HTTP 配置与业务主动 JSON 操作分别验证，原生树类型不应进入新的业务公共接口。

<a id="reference"></a>
## API 索引

| API | 入口 |
| --- | --- |
| [Json](../json-facade-api/src/main/java/org/jetlinks/json/Json.java) | builder、global、命名注册、工厂注册 |
| [JsonMapperBuilder](../json-facade-api/src/main/java/org/jetlinks/json/JsonMapperBuilder.java) | Provider、options、module、configureNative |
| [JsonMapper](../json-facade-api/src/main/java/org/jetlinks/json/JsonMapper.java) | read/convert 的 Class/JsonType、write、树工厂、游标/会话 |
| [JsonType](../json-facade-api/src/main/java/org/jetlinks/json/JsonType.java) | of、listOf、mapOf、泛型捕获 |
| [JsonOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonOptions.java) | 共享配置与 withX |
| [JsonValue](../json-facade-api/src/main/java/org/jetlinks/json/JsonValue.java) | 树访问、转换、断言、位置、复制 |
| [JsonPointer](../json-facade-api/src/main/java/org/jetlinks/json/JsonPointer.java) | RFC 6901 路径 |
| [JsonReader](../json-facade-api/src/main/java/org/jetlinks/json/JsonReader.java) / [JsonWriter](../json-facade-api/src/main/java/org/jetlinks/json/JsonWriter.java) | 同步游标 |
| [CloseableIterator](../json-facade-api/src/main/java/org/jetlinks/json/CloseableIterator.java) | iterateArray 的按需根数组迭代 |
| [JsonReadSession](../json-facade-api/src/main/java/org/jetlinks/json/JsonReadSession.java) | feed/read/endInput/close、Receiver |
| [JsonReadOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonReadOptions.java) / [JsonWriteOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonWriteOptions.java) | 分帧、选择、null、预算 |
| [JsonEvent](../json-facade-api/src/main/java/org/jetlinks/json/JsonEvent.java) | 结构 token / 有界值 |
| [JsonStreamMapper](../json-facade-reactor/src/main/java/org/jetlinks/json/reactor/JsonStreamMapper.java) | byte[] / ByteBuffer / transport SPI 流 |
| [NettyJsonStreamMapper](../json-facade-netty/src/main/java/org/jetlinks/json/netty/NettyJsonStreamMapper.java) | ByteBuf 借用/接管、输出 |
| [DataBufferJsonStreamMapper](../json-facade-spring/src/main/java/org/jetlinks/json/spring/DataBufferJsonStreamMapper.java) | DataBuffer 接管与输出 |
| [Transport SPI](../json-facade-reactor/src/main/java/org/jetlinks/json/reactor/spi) | 输入租约、输出分配器 |
| [Provider SPI](../json-facade-api/src/main/java/org/jetlinks/json/spi) | 引擎、模块、适配器 |
| [公共契约](contracts.md) / [CI](../.github/workflows/verify.yml) | 完整行为、验证与门禁 |
