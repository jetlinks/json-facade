# json-facade 使用手册

适用版本：`1.0.0-SNAPSHOT`，Java 17 及以上。本文基于当前公开 API，介绍依赖、配置、绑定、树、扩展和同步/响应式流处理。精确的行为约束见 [公共契约](contracts.md)，项目入口见 [README](../README.md)。

## 目录

- [1. 选择合适的入口](#choose)
- [2. 安装与快速开始](#start)
- [3. Provider 与实例管理](#provider)
- [4. 通用配置与限制](#options)
- [5. 对象绑定、泛型与转换](#binding)
- [6. JsonObject、JsonArray 与路径访问](#tree)
- [7. 自定义类型适配器](#adapter)
- [8. Provider 原生配置](#native)
- [9. 同步游标与文件处理](#cursor)
- [10. Reactor 流式读取](#read-stream)
- [11. Reactor 分块写出](#write-stream)
- [12. 复杂结构的事件流](#events)
- [13. 手动增量解析会话](#session)
- [14. 资源、线程与内存所有权](#ownership)
- [15. 大文档与性能实践](#performance)
- [16. 错误处理与常见问题](#errors)
- [17. Provider 扩展与迁移](#migration)
- [18. API 索引与验证入口](#reference)

<a id="choose"></a>
## 1. 选择合适的入口

门面用于应用主动发起的 JSON 操作。它不自动注册 Spring Bean，也不接管 HTTP、WebClient、SSE 或 ORM 的 JSON 配置；这些集成仍由对应框架负责。

| 业务需求 | 推荐入口 | 物化单位 |
| --- | --- | --- |
| 配置、缓存值、消息中的普通 JSON | `JsonMapper.read/write` | 一个完整值 |
| DTO、嵌套泛型、Map 与对象转换 | `JsonType`、`convert` | 一个完整值 |
| 动态字段、小型文档增删改 | `createObject/createArray/readTree` | 整棵树 |
| 同步大文件、逐项处理根数组 | `JsonReader` / `JsonWriter` | 当前 token 或绑定值 |
| 网络/文件字节流中的原生 JSON 数组 | `ARRAY_ELEMENTS` / `Format.ARRAY` | 一个数组元素 |
| 日志、批量导入导出的逐行记录 | `NDJSON` | 一行记录 |
| `{meta, data:{items:[...]}, summary}` 中的条目 | JSON Pointer selection | 一个选中元素 |
| 保留全部层级，复制或生成超大复杂文档 | `readEvents/writeEvents` | 一个 token 或 `value` 事件 |
| 自有事件循环，不引入 Reactor | `readSession/eventSession` | 一个结果 |

流式处理能够避免收集整个聚合文档。单个 POJO、字符串或数字仍须在对应操作中完整处理；例如 1 GiB 的记录数组可以逐条处理，一个 1 GiB 的字符串不能靠切输入块变成小 token。

<a id="start"></a>
## 2. 安装与快速开始

### 2.1 Maven 依赖

当前快照尚未发布到 Maven Central。先在源码根目录安装，或将构件发布到应用可访问的内部制品库：

```sh
mvn -B -ntp install
```

构建的聚合覆盖率检查还需要 Python 3。应用添加 API 和一个 Provider；响应式操作再添加 Reactor 模块。以下选择 Jackson 2：

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
    <!-- 仅流式应用需要 -->
    <dependency>
        <groupId>org.jetlinks</groupId>
        <artifactId>json-facade-reactor</artifactId>
        <version>${json-facade.version}</version>
    </dependency>
</dependencies>
```

改用 Jackson 3 时，将 Provider 构件替换为 `json-facade-jackson3`，代码选择 `jackson3`。当前工程采用 Jackson 2 `2.22.3`、Jackson 3 `3.0.3`、Reactor `3.7.18`。覆盖依赖版本后应重新验证真实引擎兼容性。`json-facade-tck` 是 Provider 测试工具，`json-facade-coverage` 是构建聚合模块，应用不需要引入它们。

### 2.2 第一个完整示例

保存为 `QuickStart.java`，在已配置上述依赖的 Java 工程运行：

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
        List<Item> items = mapper.read("[" + json + "]", JsonType.listOf(JsonType.of(Item.class)));
        System.out.println(item.id() + ": " + items.size()); // device-1: 1
    }
}
```

下文片段沿用 `public record Item(String id, int count) { }`、已构建的 `JsonMapper mapper` 和 `JsonStreamMapper streams = JsonStreamMapper.of(mapper)`。除特别注明外，片段放在应用方法内，按需导入：

```java
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.jetlinks.json.*;
import org.jetlinks.json.reactor.JsonStreamMapper;
import org.jetlinks.json.spi.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
```

生产代码可以按项目规范改成显式导入。标明为方法或类型声明的片段放在类体中。

<a id="provider"></a>
## 3. Provider 与实例管理

```java
// 按 ServiceLoader 发现的 id 选择。
JsonMapper mapper = Json.builder().provider("jackson2").build();

// 直接提供工厂，绕过 ServiceLoader 发现。
JsonMapper explicit = Json.builder()
    .provider(new org.jetlinks.json.jackson2.Jackson2Provider())
    .build();

// 插件环境可限定发现范围。
JsonMapper isolated = Json.builder()
    .classLoader(applicationClassLoader)
    .provider("jackson2")
    .build();
```

`applicationClassLoader` 由宿主提供。Jackson 3 工厂是 `org.jetlinks.json.jackson3.Jackson3Provider`。构建器最后一次 `provider(...)` 设置生效。未显式指定时，发现范围中必须恰好有一个 Provider；多个候选、重复 Provider id 或无匹配候选都会报错，不会在解析失败后换引擎重试。

`JsonMapper` 构建完成后可跨线程共享。构建器属于装配过程，不应并发修改；reader、writer、session 和可变树均按操作创建。为不同业务构建不同实例，例如外部严格入站配置和内部兼容读取配置，构建后通过依赖注入共享；不要每条消息重新构建。

`Json.defaultMapper()` 是惰性默认实例，同样要求唯一可发现的 Provider。容器或多场景项目优先注入明确配置的实例，避免默认发现结果依赖运行时类路径。

<a id="options"></a>
## 4. 通用配置与限制

`JsonOptions` 是不可变 record，当前没有链式 options builder。默认值如下：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `losslessNumbers` | `true` | 无类型小数使用 `BigDecimal`，大整数可使用 `BigInteger`；不把一切数字强制成同一种类型 |
| `failUnknownProperties` | `false` | 绑定 DTO 时是否拒绝未知字段 |
| `failDuplicateKeys` | `false` | 是否让原生解析器拒绝对象重复字段 |
| `omitNulls` | `false` | 原生绑定时是否省略可省略的 null 属性；不是删除任意数组位置的通用开关 |
| `pretty` | `false` | 常规同步输出是否美化；流式输出始终 compact |
| `maxDepth` | `256` | 原生解析嵌套深度上限 |
| `maxStringLength` | `8 * 1024 * 1024` | 原生字符串 token 长度限制，不等同于整篇 UTF-8 字节数 |
| `maxNumberLength` | `1000` | 原生数字 token 长度限制 |
| `maxValueBytes` | `8 * 1024 * 1024` | 增量读取的物化记录和流式写出的当前值字节预算 |

沿用默认值，只打开未知字段和重复键拒绝：

```java
JsonOptions defaults = JsonOptions.DEFAULT;
JsonOptions strict = new JsonOptions(
    defaults.losslessNumbers(), true, true,
    defaults.omitNulls(), defaults.pretty(), defaults.maxDepth(),
    defaults.maxStringLength(), defaults.maxNumberLength(), defaults.maxValueBytes());
JsonMapper strictMapper = Json.builder().provider("jackson2").options(strict).build();
```

重复键检测用于拒绝 `{"role":"user","role":"admin"}` 一类存在歧义的输入。检测由实际 Provider 的原生解析器执行，门面不另写 JSON 语法解析。默认关闭；业务需要明确拒绝歧义时开启。检测器会为每个尚未关闭的对象记录已见字段名，超多字段的大对象会增加内存占用。

`maxValueBytes` **不限制同步 `read/write/convert` 整值的总字节数**，也不限制整个流的累计长度。同步大值、源 chunk 大小、累计字节数和超时由宿主另行限制。

流操作的有效值预算取 mapper 与本次 options 的较小值。`JsonReadOptions.of(...)` 和 `JsonWriteOptions.of(...)` 使用静态默认 8 MiB；只增大 mapper 的限制不会增大操作默认限制，需要两边同时设置。不要为处理大数组直接放宽单条记录限制，通常应逐元素处理。

<a id="binding"></a>
## 5. 对象绑定、泛型与转换

### 5.1 常规读写

```java
String json = mapper.write(new Item("device-1", 3));
byte[] utf8 = mapper.writeBytes(new Item("device-1", 3));
Item fromText = mapper.read(json, Item.class);
Item fromBytes = mapper.read(utf8, JsonType.of(Item.class));
```

字节 API 使用 UTF-8。同步 `read` 要求恰好一个完整根值，拒绝后续非空白内容；空输入不是 JSON `null`。读取 `"null"` 到引用类型可以返回 Java null。同步 reader/writer 的底层 I/O 仍可能阻塞，不能因为外层用了 Reactor 就把它当成非阻塞操作。

### 5.2 嵌套泛型

```java
JsonType<List<Map<String, Item>>> nested = new JsonType<List<Map<String, Item>>>() { };
List<Map<String, Item>> result = mapper.read(
    "[{\"first\":{\"id\":\"device-1\",\"count\":3}}]", nested);

JsonType<List<Item>> itemList = JsonType.listOf(JsonType.of(Item.class));
JsonType<Map<String, Item>> itemMap =
    JsonType.mapOf(JsonType.of(String.class), JsonType.of(Item.class));
```

匿名类必须直接继承带实参的 `JsonType<...>`。运行时已有 `java.lang.reflect.Type` 可用 `JsonType.of(type)`，返回 `JsonType<?>`。不要用未绑定类型变量的 `new JsonType<List<T>>() { }` 期待自动恢复运行时 `T`。

写入入口接受 `Object`，当前没有 `write(value, JsonType)`。依赖声明泛型或多态类型标记的场景，尤其根集合，需用真实业务样本验证原生配置；不要假定泛型读取与写出完全对称。必要时使用显式 DTO 包装或自己的适配器。

### 5.3 对象转换与数字精度

```java
Item item = mapper.convert(Map.of("id", "device-1", "count", 3), JsonType.of(Item.class));
Map<String, Object> fields = mapper.convert(item,
    new JsonType<Map<String, Object>>() { });
Object precise = mapper.read("0.1234567890123456789", Object.class); // 默认 BigDecimal
```

`convert` 使用当前 Provider 的 JSON 绑定语义，包含命名策略、适配器和属性规则。它不是保留引用的 bean copy，也不承诺零序列化或零分配。绑定到明确的 `double` 仍采用该目标类型精度。

日期、Optional、枚举、二进制、多态和注解规则以实际 Provider 配置为准。Jackson 2 默认需要额外官方模块支持 `java.time` / `Optional`，参见 [原生配置](#native)。原生绑定通常将 `byte[]` 写成 Base64 字符串；结构树工厂对 Java 数组的处理不同，见下一节。

<a id="tree"></a>
## 6. JsonObject、JsonArray 与路径访问

### 6.1 创建、修改、读取

```java
JsonObject root = mapper.createObject(Map.of("version", 1));
JsonArray items = mapper.createArray(List.of(Map.of("id", "device-1", "count", 3)));
root.put("items", items).put("note", null);
root.get("items").asArray().add(Map.of("id", "device-2", "count", 4));
root.remove("version");

JsonValue id = root.at(JsonPointer.of("/items/0/id"));
String idText = (String) id.toJavaValue();
boolean explicitNull = root.get("note").isNull();     // true
boolean absent = root.get("unknown").isMissing();     // true
String json = mapper.write(root);
```

工厂放在 `JsonMapper` 上，Provider 可以选择存储实现，应用依赖中立接口。当前默认使用 JDK 容器。`JsonValue.object()/array()` 是不带配置的结构快捷方式。

初始 Map/Collection 和 `put/add/set` 会复制嵌套容器，包括已有容器节点。插入 `items` 后再改原 `items`，不会修改 `root` 中的副本；修改应从 `root.get("items")` 取得节点。输入须无环，对象键必须是 String，显式 null 会保留。

`get/at` 缺失时返回 `JsonValue.MISSING`，显式 null 返回 `JsonValue.NULL`，两者都不是 Java null。调用 `MISSING.toJavaValue()` 会失败，应先检查。`asObject/asArray` 只接受对应形状，类型错误会抛异常。

JSON Pointer 遵循 RFC 6901：空指针 `""` 表示根，`/a~1b/~0key` 访问键 `a/b` 下的 `~key`；数组索引使用 `0`、`1` 等规范十进制，`01` 和 `-` 不作为可读取索引。它不是 JSONPath，不支持通配或条件筛选。

### 6.2 结构复制与绑定转换

```java
JsonValue parsed = mapper.readTree("{\"items\":[{\"id\":\"device-1\",\"count\":3}]}");
JsonValue fromBean = mapper.toTree(new Item("device-1", 3));
JsonObject independent = root.deepCopy();
Object snapshot = root.toJavaValue(); // 独立的 Map/List 图

String boundBinary = mapper.write(Map.of("data", new byte[] {1, 2}));
String treeBinary = mapper.write(mapper.createObject(Map.of("data", new byte[] {1, 2})));
// 默认绑定：{"data":"AQI="}；结构树：{"data":[1,2]}
```

`createObject/createArray/JsonValue.of` 接受 JSON 兼容标量、Map、Collection 和 Java 数组，不对任意 POJO、Instant 等执行绑定；需要应用命名、日期、适配器或非 String Map 键策略时使用 `mapper.toTree(value)`。`JsonValue.of(existingNode)` 保留节点身份，若要隔离请用 `deepCopy()`。

树当前没有字段迭代器、`asString/asInt` 等标量快捷 getter，也没有数组 `remove/insert/clear`。读取标量用 `toJavaValue()` 并检查实际类型；遍历未知对象字段可导出 Map 快照，但会复制。需要高吞吐遍历大文档时用游标或事件，避免反复导出。当前默认树节点的 `equals` 不做结构比较；需要比较 JSON 数据时可比较导出的 Java 值，并另外明确数字类型/scale 的比较规则。

<a id="adapter"></a>
## 7. 自定义类型适配器

中立扩展适合业务值对象编码。以下声明放在类体中，将 `DeviceId` 写成字符串：

```java
public record DeviceId(String value) { }

public static final class DeviceIdModule implements JsonModule {
    @Override
    public String id() {
        return "device-id";
    }

    @Override
    public void configure(JsonModuleContext context) {
        context.add(DeviceId.class, new JsonAdapter<DeviceId>() {
            @Override
            public void write(DeviceId value, JsonWriter writer) {
                writer.string(value.value());
            }

            @Override
            public DeviceId read(JsonReader reader) {
                return new DeviceId(reader.readValue(JsonType.of(String.class)));
            }
        });
    }
}
```

注册和使用：

```java
JsonMapper customized = Json.builder().provider("jackson2")
    .module(new DeviceIdModule()).build();
String encoded = customized.write(new DeviceId("device-1")); // "device-1"
DeviceId decoded = customized.read(encoded, DeviceId.class);
```

适配器按**精确 Class** 匹配，不自动覆盖子类/实现类，也没有按泛型 Type 注册的入口。模块 id 与同一 mapper 的 Class 注册不能重复。

适配器同步运行于调用线程，实例须线程安全。reader/writer 是借用游标，不能关闭或保留；读取须消费当前完整值，写入须产生恰好一个值。`writer.value(...)` 使用正常绑定，不能把同一个待编码对象重新交给自己的适配器，否则会递归。`string/number/bool/nullValue` 直接写 token，绕过相应 Java 标量类型适配器。

Java null 不调用适配器，遵循 mapper 的 null 策略。模块装配和适配器中不要执行远程 I/O；业务异步操作放在 JSON 转换前后的响应式链路中。

<a id="native"></a>
## 8. Provider 原生配置

### 8.1 命名策略和原生模块

通过构建期的 `configureNative` 借用原生 builder，无需门面为每个引擎功能增加专用方法：

```java
JsonMapper snakeCase = Json.builder().provider("jackson2")
    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
        nativeBuilder -> nativeBuilder.propertyNamingStrategy(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE))
    .build();

JsonMapper snakeCase3 = Json.builder().provider("jackson3")
    .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class,
        nativeBuilder -> nativeBuilder.propertyNamingStrategy(
            tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE))
    .build();
```

Jackson 2 使用日期/Optional 时，应用额外添加与 Jackson 匹配的依赖：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jsr310</artifactId>
    <version>2.22.3</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jdk8</artifactId>
    <version>2.22.3</version>
</dependency>
```

```java
JsonMapper dates = Json.builder().provider("jackson2")
    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class, nativeBuilder -> {
        nativeBuilder.addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        nativeBuilder.addModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module());
        nativeBuilder.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    })
    .build();
String instant = dates.write(java.time.Instant.parse("2026-01-01T00:00:00Z"));
```

Jackson 3 的这些常见 JDK 类型支持已并入引擎；迁移时仍应验证时间字符串、时区、精度和 Optional 空值行为，不照搬 Jackson 2 模块坐标。

### 8.2 Mixin 示例

以下类型声明使用 Jackson 2 的注解，隐藏第三方 DTO 的敏感属性：

```java
public record Account(String name, String secret) { }

public abstract static class AccountMixin {
    @com.fasterxml.jackson.annotation.JsonIgnore
    public abstract String secret();
}
```

```java
JsonMapper masked = Json.builder().provider("jackson2")
    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
        nativeBuilder -> nativeBuilder.addMixIn(Account.class, AccountMixin.class))
    .build();
String accountJson = masked.write(new Account("operator", "private")); // 仅 name
```

### 8.3 配置优先级与生命周期

每次 `build()` 创建独立的原生 builder。所有回调类型先整体检查，再按注册顺序同步执行；类型不匹配在执行任何回调前失败。回调失败包装为 `CONFIGURATION` 并保留 cause。回调可用于原生模块、mixin、命名和日期格式，不能保留 builder、发布它、做 I/O 或修改已发布 mapper。

`JsonOptions` 管理的共享策略具有权威性。原生回调如果更改 pretty、未知字段、无损数字、重复键、全局 null inclusion 或受管限制，导致与 `JsonOptions` 冲突，构建会失败。单根读取、调用方 I/O 所有权和不自动修补残缺文档的约束也不能关闭。调整共享策略请设置 `JsonOptions`，不要依赖回调覆盖顺序。构建校验针对配置值，不会证明用户自定义序列化器的任意代码都遵守约束。

门面没有共享可变原生 mapper getter。必须执行原生专有操作的代码应在明确的集成边界管理自己的原生实例。旧实验入口 `Jackson2Extension/Jackson3Extension` 已移除，当前统一使用 `configureNative`。

<a id="cursor"></a>
## 9. 同步游标与文件处理

### 9.1 调用方管理文件资源

以下为类内方法声明。`InputStream/Reader` 读取需传 `JsonType`，没有 Class 重载：

```java
static Item readFile(JsonMapper mapper, Path path) throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
        return mapper.read(input, JsonType.of(Item.class));
    }
}

static void writeFile(JsonMapper mapper, Path path, Item item) throws IOException {
    try (OutputStream output = Files.newOutputStream(path)) {
        mapper.write(item, output);
    }
}
```

`write(value, Writer)` 与 `read(Reader, JsonType)` 处理字符；字节 API 使用 UTF-8。门面不关闭传入的 I/O，文件、Socket 等真实资源由创建它们的调用方关闭。

### 9.2 逐项读取同步数组

以下方法不收集数组，`consume` 逐条处理有限大小的 Item：

```java
static void consumeArray(JsonMapper mapper, InputStream input, Consumer<Item> consume) {
    try (JsonReader reader = mapper.reader(input)) {
        if (reader.next() != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("Expected a root array");
        }
        JsonToken token;
        while ((token = reader.next()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw new IllegalArgumentException("Incomplete array");
            }
            consume.accept(reader.readValue(JsonType.of(Item.class)));
        }
        if (reader.next() != null) {
            throw new IllegalArgumentException("Unexpected trailing value");
        }
    }
}
```

`readValue` 消费整个当前值，下次 `next()` 返回其后继。`skipValue()` 同样跳过当前完整子树，不物化它；`next()` 返回 null 才是 EOF。`text()` 可读取当前字段名或标量文本。游标本身允许调用方手动组织处理，示例额外检查尾部以保证单根文档。

### 9.3 逐项写出同步数组

```java
static void writeArray(JsonMapper mapper, OutputStream output, Iterable<Item> items) {
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

`writer(...)` 遵循同步 pretty 策略，`compactWriter(...)` 为当前操作选择 compact，`writeCompact(value, output)` 则直接写一个 compact 值。它们不修改共享 mapper。`compactWriter` 不自动在多个根值间插入分隔符；多记录分帧由调用方负责。

`beginObject/endObject/name` 用于对象，`string/bool/nullValue/number` 直接写标量。`number("1.2300e+10")` 校验 JSON 数字语法，无需转成 double，也不能用来注入 raw JSON。关闭 writer 释放状态但不补缺失括号；业务失败时不能把残缺输出当作成功文档。

<a id="read-stream"></a>
## 10. Reactor 流式读取

### 10.1 根数组、NDJSON 与多根值

以下输入只是便于展示的内存样本，真实业务可直接传入网络或文件读取产生的 `Publisher<byte[]>`：

```java
Flux<byte[]> chunks = Flux.just(
    "[{\"id\":\"device-1\",".getBytes(StandardCharsets.UTF_8),
    "\"count\":3},{\"id\":\"device-2\",\"count\":4}]".getBytes(StandardCharsets.UTF_8));
Flux<Item> items = streams.readBytes(chunks, JsonType.of(Item.class),
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));

Flux<byte[]> lines = Flux.just(
    "{\"id\":\"device-1\",\"count\":3}\n{\"id\":\"device-2\",\"count\":4}\n"
        .getBytes(StandardCharsets.UTF_8));
Flux<Item> imported = streams.readBytes(lines, JsonType.of(Item.class),
    JsonReadOptions.of(JsonReadOptions.Mode.NDJSON));
```

| 读取模式 | 输入示例 | 发出内容 | 空输入 |
| --- | --- | --- | --- |
| `SINGLE_VALUE` | `[1,2]` | 一个 List（目标类型须匹配） | 失败 |
| `ARRAY_ELEMENTS` | `[1,[2,3]]` | `1`、完整的 `[2,3]` | 失败；`[]` 正常完成 |
| `NDJSON` | 每行一个值 | 每个非空行的值 | 零条记录 |
| `ROOT_VALUES` | `1 true {"a":2}` | 三个完整根值 | 零条记录 |

NDJSON 支持 LF/CRLF、空白行和无末尾换行，拒绝跨行记录或一行多个根值。ROOT_VALUES 要求 JSON 空白分隔，不自动展开数组。输入 chunk 可以切在任意 UTF-8/token 位置；它既不是记录边界，也不是真 EOF。不要自行按 chunk 调用 `mapper.read`。

### 10.2 从包装文档中选取数组

```java
Flux<byte[]> wrapped = Flux.just(
    "{\"meta\":{\"version\":1},\"data\":{\"items\":[{\"id\":\"device-1\",\"count\":3}]},\"summary\":{}}"
        .getBytes(StandardCharsets.UTF_8));
JsonReadOptions selected = new JsonReadOptions(
    JsonReadOptions.Mode.SINGLE_VALUE,
    new JsonReadOptions.Selection(JsonPointer.of("/data/items"), true),
    JsonReadOptions.NullPolicy.FAIL, 8 * 1024 * 1024);
Flux<Item> selectedItems = streams.readBytes(wrapped, JsonType.of(Item.class), selected);
```

`Selection(..., true)` 发出目标数组的直接元素，`false` 绑定目标完整值。selection 仅允许 SINGLE_VALUE。路径缺失、目标形状错误都会失败，空选中数组正常完成。

无关子树仍由原生解析器校验并受 token/depth 限制，但不收集成树。选中路径重复出现始终失败，即使全局重复键检测关闭，因为已经发出的元素无法撤回以模拟最后值覆盖。消费者需要验证整篇文档时，必须消费至完成；`take(1)` 或取消只验证已经消费的前缀。

### 10.3 null 和 ByteBuffer

```java
Flux<JsonValue> nodes = streams.readBytes(
    Flux.just("[null,{\"id\":\"device-1\"}]".getBytes(StandardCharsets.UTF_8)),
    JsonType.of(JsonValue.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
// 第一项是非 Java null 的 JsonValue.NULL。

JsonReadOptions skipNulls = new JsonReadOptions(JsonReadOptions.Mode.ARRAY_ELEMENTS,
    null, JsonReadOptions.NullPolicy.SKIP, 8 * 1024 * 1024);
Flux<Item> nonNull = streams.readBytes(
    Flux.just("[null,{\"id\":\"device-1\",\"count\":3}]".getBytes(StandardCharsets.UTF_8)),
    JsonType.of(Item.class), skipNulls);

Flux<Item> fromBuffers = streams.readBuffers(
    Flux.just(ByteBuffer.wrap("[]".getBytes(StandardCharsets.UTF_8))),
    JsonType.of(Item.class), JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
```

Reactor 不能发出 Java null，默认 `NullPolicy.FAIL`。明确不需要 null 记录时用 SKIP；需要保留时读为 `JsonValue`。`readBuffers` 支持堆、直接、只读和 slice buffer，保留输入 position/limit/mark；具体生命周期见 [资源所有权](#ownership)。

### 10.4 连接业务处理

以下为类内方法声明，保存操作由调用方提供。返回的 Mono 交给宿主订阅：

```java
static Mono<Void> importItems(JsonStreamMapper streams, Flux<byte[]> chunks,
                              java.util.function.Function<Item, Mono<Void>> save) {
    return streams.readBytes(chunks, JsonType.of(Item.class),
            JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS))
        .concatMap(save, 0)
        .then();
}
```

串行保存保留顺序；业务可按数据库/远程服务容量设计有限并发或有限批次。不要在业务链里 `block()`、内部 `subscribe()` 或收集全部记录。解析失败前已经完成的保存不会回滚，导入的事务、幂等键、失败记录和重试边界由业务设计。不能用 `onErrorContinue` 恢复一个已经损坏的解析会话。

<a id="write-stream"></a>
## 11. Reactor 分块写出

```java
Flux<Item> items = Flux.just(new Item("device-1", 3), new Item("device-2", 4));
JsonWriteOptions arrayOutput = new JsonWriteOptions(
    JsonWriteOptions.Format.ARRAY, 16 * 1024, 8 * 1024 * 1024);
Flux<byte[]> arrayBytes = streams.writeBytes(items, arrayOutput);
Flux<ByteBuffer> ndjsonBuffers = streams.writeBuffers(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON));
```

将结果直接交给宿主响应/文件写入组件；不要为了输出再 `collectList()` 或拼成一个 String。这里的 `items` 是冷的内存示例，可独立订阅；真实单次消费数据源不能假定可重复订阅。

| 写出格式 | 行为 | 空源 |
| --- | --- | --- |
| `SINGLE_VALUE` | 必须恰好一条值 | 失败，多条也失败 |
| `ARRAY` | 数组包围所有记录 | `[]` |
| `NDJSON` | 每条 compact 值后接 LF | 零字节 |
| `ROOT_VALUES` | 当前实现同样以 LF 分隔根值 | 零字节 |

`JsonWriteOptions.of` 默认 `chunkSize=8192`、`maxValueBytes=8 MiB`。chunkSize 控制分段大小，分段不对应记录、字段或 UTF-8 字符边界，消费端应按字节流处理。记录字节预算与传输分隔符分开核算。

流输出始终 compact，即使共享 mapper 开启 pretty；其他绑定模块和策略继续生效。内部按订阅复用原生 writer，但每个绑定值使用新的绑定上下文，避免对象 ID 状态跨记录累积。

背压控制下游可见的段，不会把任意 POJO 的原生序列化暂停在任意字节处。一个有界 POJO 会完整同步编码为自有分段，再逐段交付；需更细粒度推进大型聚合时使用事件结构。错误和取消不会补正常的数组结束括号，已经发出的字节无法回滚；多个 SINGLE_VALUE 的错误也可能发生在第一条字节发出之后。

<a id="events"></a>
## 12. 复杂结构的事件流

### 12.1 元数据、流式数组与尾部汇总

以下声明为类内方法。`Flux.defer` 为每次订阅独立计算汇总；没有收集 items：

```java
static Flux<byte[]> exportDocument(JsonStreamMapper streams, Flux<Item> items) {
    return Flux.defer(() -> {
        java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong();
        Flux<JsonEvent> document = Flux.concat(
            Flux.just(JsonEvent.START_OBJECT,
                JsonEvent.name("meta"), JsonEvent.value(Map.of("version", 1)),
                JsonEvent.name("items"), JsonEvent.START_ARRAY),
            items.doOnNext(item -> count.incrementAndGet()).map(JsonEvent::value),
            Flux.defer(() -> Flux.just(JsonEvent.END_ARRAY,
                JsonEvent.name("summary"), JsonEvent.value(Map.of("count", count.get())),
                JsonEvent.END_OBJECT)));
        return streams.writeEvents(document,
            JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE));
    });
}
```

成功输出结构为 `{"meta":{"version":1},"items":[...],"summary":{"count":N}}`。汇总只在 items 正常完成后生成；上游出错会终止，不产生成功尾部。每次订阅状态独立，但输入 `items` 本身是否可重复消费仍由源决定。

### 12.2 完整结构转发

```java
Flux<byte[]> input = Flux.just(
    "{\"meta\":{},\"items\":[1,{\"nested\":[true,null]}],\"summary\":{}}"
        .getBytes(StandardCharsets.UTF_8));
Flux<JsonEvent> events = streams.readEvents(input);
Flux<byte[]> copied = streams.writeEvents(events,
    JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE));
```

ByteBuffer 对应 `readBufferEvents` 和 `writeEventBuffers`。事件读取处理恰好一个完整文档，直接发出所有结构/字段/标量，不收集包围容器。复制保持 JSON 结构和 tokenizer 提供的标量表示，不保留原始空白、转义方式或源文本；两套原生非阻塞 tokenizer 都会把整数 `-0` 规范成 `0`。若业务依赖原始签名文本，应保留原始字节。

### 12.3 事件模型

| 事件 | 用途 |
| --- | --- |
| `START_OBJECT/END_OBJECT`、`START_ARRAY/END_ARRAY` | 容器边界，复用不可变常量 |
| `name(String)` | 对象字段名 |
| `string(String)`、`number(String)` | 文本标量；数字校验语法，不经过 double |
| `TRUE/FALSE/NULL` | 直接标量常量 |
| `value(Object)` | 用 mapper 绑定一个完整 Java 值，可包含 Java null |
| `token(JsonToken, String)` | 按 token 类型创建事件，容器不带 payload |

`writeEvents` 仅接受 SINGLE_VALUE，校验容器配对、对象字段/值顺序和唯一根值。手动构造事件的无效数字在写入时被拒绝。直接标量事件绕过 Java 标量绑定适配器，`value(...)` 使用正常绑定。

写入按事件排空，后续事件不会越过当前事件；一个 `value(hugePojo)` 仍是完整绑定单位。非常大的对象应拆成 `START_OBJECT`、逐字段事件、`END_OBJECT`；很大的数组应拆成数组边界与逐项事件。单个超长字符串/数字不会被结构事件自动拆开，仍受 token 与当前值预算限制。

<a id="session"></a>
## 13. 手动增量解析会话

不使用 Reactor 的宿主可以直接驱动 Provider 的 UTF-8 session。下面是类内方法，输入为逐块产生且生命周期稳定的 ByteBuffer，输出由 Consumer 接收；该方法不负责关闭输入来源：

```java
static <T> void pump(JsonMapper mapper, Iterator<ByteBuffer> chunks, JsonType<T> type,
                     JsonReadOptions options, Consumer<T> consume) {
    try (JsonReadSession<T> session = mapper.readSession(type, options)) {
        boolean ended = false;
        while (true) {
            JsonReadSession.Result<T> result = session.read();
            switch (result.state()) {
                case VALUE -> consume.accept(result.value()); // 允许 Java null
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

调用样例：

```java
Iterator<ByteBuffer> chunks = List.of(
    ByteBuffer.wrap("[1,".getBytes(StandardCharsets.UTF_8)),
    ByteBuffer.wrap("2]".getBytes(StandardCharsets.UTF_8))).iterator();
pump(mapper, chunks, JsonType.of(Integer.class),
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS), System.out::println);
```

只在 `needsInput()` 为 true 时 `feed`。session 借用 buffer 的 remaining 区域，不修改 position/limit/mark；在下次 `needsInput()` 为 true 或 close 前，源内存必须保持有效且内容不变。它与 Reactor `readBuffers` 的立即复制行为不同。

VALUE 可携带 Java null，不能据此判断 EOF。只有真实输入结束才调用一次 `endInput()`，之后继续 `read()` 直到 END，残缺文档会失败。实际事件循环在 NEED_INPUT 时应等待异步来源，不能忙轮询；示例 Iterator 只是演示状态机。

`mapper.eventSession()` 使用同样的 feed/read/endInput/close 协议，输出 `JsonEvent`。`read(BooleanSupplier cancelled)` 可提供线程安全、非阻塞取消谓词；取消可能返回 END 而未校验剩余文档，调用方仍须 close。

<a id="ownership"></a>
## 14. 资源、线程与内存所有权

| 对象 | 生命周期与责任 |
| --- | --- |
| `JsonMapper` / `JsonStreamMapper` | 构建后共享；无 close，不持有每次操作状态 |
| Builder / 可变树 | 操作局部，不并发修改 |
| Reader / Writer / Session | 每操作一个，try-with-resources 关闭；释放原生状态，不关闭调用方 I/O |
| 同步传入的文件/流/Writer | 创建者关闭；阻塞特性由底层 I/O 决定 |
| Reactor 输入 `byte[]` | 发布后保持内容不变，onNext 返回也不能复用存储 |
| Reactor 输入 `ByteBuffer` | 在适配器自己的输入 onNext 内立即复制 remaining 区域，该调用返回后可复用源存储 |
| 手动 session 的 `ByteBuffer` | 借用到下次 needsInput 或 close，期间不能改底层字节 |
| 输出 `byte[]` / `ByteBuffer` | 库拥有独立存储并转交下游，不再复用；ByteBuffer 只读且 cursor 独立，无 release |
| Netty ByteBuf / Spring DataBuffer | 门面不推断 retain/release；宿主在转换、交付和释放时保证生命周期 |

如果在进入 `readBuffers` 前使用 `publishOn` 等异步边界，宿主必须保证队列中的原始内存一直有效到适配器实际收到它；立即复制不能保护尚未到达适配器的字节。直接构造只读 view 也不能隔离可复用的底层内存。

发布者懒执行，每次订阅创建独立解析/写入状态，保留 Reactor Context 和上游异常身份。内部没有主动 `subscribe/block`、强制 Scheduler 或整流收集。取消向上游传播，正常完成、错误和取消均释放操作状态；正在运行的自定义绑定回调须返回后才能完成相应清理，不能在其中无限阻塞。

库不会代替宿主释放真实来源、回滚业务副作用或关闭连接。关闭后的包装器会断开曾借用的输入/输出和原生上下文引用；宿主仍应在不再使用时释放自己的引用。不要跨线程共享一个 session，或在 JSON 处理尚未完成时修改待写对象和事件中的 Java 值。

<a id="performance"></a>
## 15. 大文档与性能实践

### 15.1 分别控制不同预算

| 预算 | 控制方式 |
| --- | --- |
| 整篇文档/整个连接累计字节、超时 | 宿主计数、网络/文件策略和 Reactor 超时 |
| 当前输入 chunk | 上游读取块大小；不要一次发送整篇文件 |
| 单个字符串/数字与嵌套深度 | `JsonOptions` 原生 token/depth 限制 |
| 物化记录/绑定值 | mapper 与操作 `maxValueBytes` 的较小值 |
| 待输出分段 | chunkSize 与当前值预算；段大小不等于总待输出内存 |
| 下游业务缓存与并发 | concatMap/flatMap 并发、批次、预取和宿主队列 |
| 开启重复键检测的对象字段集合 | 原生检测器；减少单个巨型对象的字段数或调整输入结构 |

“请求一条”不等于严格的一条 token CPU、固定堆字节数或零预取；解析器可能继续扫描以确定值完整性，链路也可能准备下一个有限记录。单条记录虽有字节预算，反序列化后的 Java 对象开销仍可能更大。

### 15.2 减少不必要的物化和分配

1. 共享 mapper 与不可变 JsonType，按订阅创建操作状态。
2. 入站根数组用 ARRAY_ELEMENTS，包装数组用 selection，出站用 ARRAY 或结构事件；不要先生成整树。
3. 字节消费者直接接收字节/输出流，避免 `write -> String -> getBytes`。
4. 已知 DTO 直接绑定，避免 `readTree -> toJavaValue -> convert` 的多层中间表示。
5. 未知大结构用 cursor/events，避免对每个字段导出整棵 Map 快照。
6. 单条业务值保持有界，源块和输出段从默认 8 KiB 起按真实负载测量；极小分段会增加数组、队列和信号开销。
7. 保持有限并发，检查业务 `cache/replay/buffer/collectList` 是否把整流重新保存在堆中。

库会复用订阅内的原生 writer 和内部状态，并把完整输出段直接移交下游；公开事件和输出内存必须能够被独立保留，不会循环复用公开可见数组。ByteBuffer 入站复制用于保护生命周期，不能仅为少一次分配就改成共享可复用存储。

### 15.3 复现大输入输出和资源验证

先完成源码构建，再在仓库根目录运行：

```sh
# 两个 Provider、六种模式、每文档至少 1 GiB，128/256 MiB 堆。
python3 scripts/run-streaming-stress.py

# 更小输入，覆盖小块和大块边界。
python3 scripts/run-streaming-stress.py --mib 16 --heaps 128 --chunks 64 65536

# 保留关闭后的包装器，检查原堆/直接内存和目标引用是否释放。
python3 scripts/run-resource-release-stress.py

# JMH 吞吐、累计分配和 GC 分析。
python3 scripts/run-streaming-benchmarks.py
```

六种场景包括根数组读取、NDJSON 读取、包装数组选择、数组写出、NDJSON 写出和复杂文档事件复制。框架按需生成并验证数据，不收集整个文档；输出独立校验结构和尾部元数据。可用 `JSON_STRESS_JAVA` 指定压力测试 JVM。

当前实现已有至少 1 GiB 聚合文档的受限堆验证。它证明这些测试场景的有界处理能力，不保证任意大 POJO、单 token 或所有并发量都使用固定内存。累计分配、GC 后存活堆、各内存池峰值之和和 RSS 是不同指标；微基准吞吐也不等同于生产网络吞吐。具体证据及源码版本见 [验证契约](contracts.md#implementation-and-validation) 与 [PR](https://github.com/jetlinks/json-facade/pull/1)。

<a id="errors"></a>
## 16. 错误处理与常见问题

### 16.1 错误分类

`JsonException.kind()` 提供稳定类别，原生 cause 保留进一步诊断：

| 类别 | 常见原因 |
| --- | --- |
| `PROVIDER` | 无匹配引擎、发现歧义或重复 Provider id |
| `CONFIGURATION` | 重复注册、原生类型不匹配、共享配置冲突 |
| `SYNTAX` | 无效 JSON、残缺输入、错误分帧 |
| `BINDING` | 无法绑定目标类型或业务适配失败 |
| `LIMIT` | token、深度或当前值预算超限 |
| `IO` | 同步底层输入/输出失败 |

```java
try {
    mapper.read("{", Item.class);
} catch (JsonException error) {
    System.err.println(error.kind() + ": " + error.getMessage());
    // 将 cause 交给宿主日志；不要记录敏感原文。
}
```

具体行列和绑定路径可能在原生 cause 中，当前没有中立 `JsonLocation`。参数、树形状或关闭后使用等 API 误用也可能抛 `IllegalArgumentException/IllegalStateException`，不能只捕获 JsonException 就认为覆盖所有编程错误。Reactor 上游错误保留原异常身份；失败后从源头重新创建一次操作才是新的解析会话。

### 16.2 常见问题

**已有 Jackson 配置为何没有生效？** 构建器创建自己的实例，不读取框架中已有的 ObjectMapper Bean。把所需规则明确注册到本 mapper，HTTP codec 仍配置在框架侧。

**为什么 selection 在关闭重复键检测后仍拒绝重复路径？** 已发出的选中值不能撤回。选中路径唯一是流式选择的独立契约，其他字段仍遵循 Provider 配置。

**提高 maxValueBytes 后仍然 8 MiB 失败？** 检查操作 options 是否仍由 `of(...)` 创建，其默认预算独立于 mapper；同时检查 token 长度与深度，它们不是同一限制。

**为什么有背压仍占用很多内存？** 检查源是否一块提供巨大字节数组、单条 POJO 是否过大、业务是否缓存、并发是否失控，以及重复键检测是否面对超多字段的对象。背压是信号需求，不是完整堆预算。

**读到了需要的条目后能证明整篇 JSON 合法吗？** 不能。需要消费到完成，才能发现尾部语法、重复路径或残缺容器错误。

**能跳过坏的 NDJSON 行继续吗？** 当前没有自动跳坏记录模式。需要容错导入时，宿主先建立独立记录边界和每行预算，再逐条使用独立解析操作并记录业务失败；不要在同一个失败 session 上恢复。

**如何输出漂亮的 NDJSON？** 流式输出始终 compact，以保证一行一个值；普通同步输出可配置 pretty。

**为何树工厂拒绝 POJO、对 byte[] 的输出也不同？** 结构工厂只复制 JSON 容器/标量；任意 Java 对象绑定请使用 `toTree`。两者的用途不同，参见 [树转换](#tree)。

**能直接写任意 raw JSON、压缩 JSON 或 CBOR 吗？** 当前没有 raw 注入接口。已有合法片段可先解析成树/事件；压缩放在宿主传输层，CBOR/Smile/XML 使用原生专用集成。

<a id="migration"></a>
## 17. Provider 扩展与迁移

### 17.1 实现新的 Provider

`org.jetlinks.json.spi.JsonProvider` 仅包含 `id()` 和 `create(JsonConfiguration)`。实现者需要返回遵守全部公共契约的 JsonMapper，而不是仅替换同步 `read/write`：

- 应用 options、中立模块与类型化原生配置；不支持的配置明确失败。
- 实现类型绑定、树兼容、借用游标和非阻塞增量读取/事件会话。
- 原生引擎负责 JSON token 解析、绑定和重复键检测，公共层负责分帧、选择和资源协调。
- 遵守 UTF-8、记录预算、EOF、取消、关闭和调用方 I/O 所有权规则。
- 在 `META-INF/services/org.jetlinks.json.spi.JsonProvider` 注册工厂全限定名，或让应用显式传工厂。
- 以测试依赖引入 `json-facade-tck`，继承 `org.jetlinks.json.tck.JsonMapperContract`，实现其 `mapper(JsonOptions, JsonModule...)`，在真实引擎上执行共享契约测试；另测 Provider 特有配置。

当前没有可选增量能力开关，只有同步 Reader 的引擎不能靠整流收集伪装成非阻塞 Provider。当前交付的实现是 Jackson 2/3，未提供 Gson/Fastjson Provider。

### 17.2 从原生 API 或另一 Provider 迁移

业务 DTO、JsonType、JsonValue、中立模块和流模式可以保留；原生注解、mixin、模块、日期/枚举/Map 键、多态和专有类型需要显式迁移。建议按业务边界替换主动 JSON 调用，在真实样本上对比字段、null、精度、日期和错误行为，再切换引擎。

不要将原生 JsonNode/ObjectNode 或特定 Provider 类放入新的业务公共接口。已有公开类型不能只改 import 就视为兼容；框架配置与业务门面配置也应分别验证。

<a id="reference"></a>
## 18. API 索引与验证入口

下表列出当前公开入口，便于查找准确签名：

| API | 用途与关键重载 |
| --- | --- |
| [Json](../json-facade-api/src/main/java/org/jetlinks/json/Json.java) | `builder`、`defaultMapper` |
| [JsonMapperBuilder](../json-facade-api/src/main/java/org/jetlinks/json/JsonMapperBuilder.java) | `provider(String/JsonProvider)`、`classLoader`、`options`、`module`、`configureNative`、`build` |
| [JsonMapper](../json-facade-api/src/main/java/org/jetlinks/json/JsonMapper.java) | `write(Object)`、`writeBytes`、`write(Object, OutputStream/Writer)`、`writeCompact(Object, OutputStream)`；`read(String/byte[], Class/JsonType)`、`read(InputStream/Reader, JsonType)`；`convert(Object, JsonType)`；`readTree(String)`、`toTree(Object)`；工厂、游标和会话 |
| [JsonType](../json-facade-api/src/main/java/org/jetlinks/json/JsonType.java) | 匿名泛型捕获、`of(Class/Type)`、`listOf`、`mapOf`、`type` |
| [JsonOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonOptions.java) | 不可变共享策略与 token/记录限制 |
| [JsonValue](../json-facade-api/src/main/java/org/jetlinks/json/JsonValue.java) | NULL/MISSING、`get/at/size`、`toJavaValue/deepCopy`、形状访问；JsonObject/JsonArray 增加对应修改操作 |
| [JsonPointer](../json-facade-api/src/main/java/org/jetlinks/json/JsonPointer.java) | RFC 6901 `of` 和解码后的 `segments` |
| [JsonReader](../json-facade-api/src/main/java/org/jetlinks/json/JsonReader.java) / [JsonWriter](../json-facade-api/src/main/java/org/jetlinks/json/JsonWriter.java) | 同步 token 游标，调用方负责关闭 |
| [JsonReadSession](../json-facade-api/src/main/java/org/jetlinks/json/JsonReadSession.java) | `needsInput/feed/read/endInput/close`，带状态结果 |
| [JsonReadOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonReadOptions.java) / [JsonWriteOptions](../json-facade-api/src/main/java/org/jetlinks/json/JsonWriteOptions.java) | 每操作分帧、选择、null 和记录/分段预算 |
| [JsonEvent](../json-facade-api/src/main/java/org/jetlinks/json/JsonEvent.java) | 不可变结构事件和有界 Java 值 |
| [JsonStreamMapper](../json-facade-reactor/src/main/java/org/jetlinks/json/reactor/JsonStreamMapper.java) | `readBytes/readBuffers/readEvents/readBufferEvents`；`writeBytes/writeBuffers/writeEvents/writeEventBuffers` |
| [SPI 包](../json-facade-api/src/main/java/org/jetlinks/json/spi) | `JsonProvider`、`JsonConfiguration`、`JsonModule`、`JsonModuleContext`、`JsonAdapter`、`JsonNativeConfiguration` |
| [JsonException](../json-facade-api/src/main/java/org/jetlinks/json/JsonException.java) | 统一类别与原生 cause |

源码工程的标准质量验证为 `mvn -B -ntp verify`，包含两套真实引擎 TCK、Reactor 契约、编译告警门禁、Checkstyle、SpotBugs 和每生产模块至少 80% 分支覆盖率；CI 在 Java 17/21 上运行。手册中业务方法返回 Publisher/Mono，由宿主统一订阅；测试或独立 main 可以在终端边界等待结果，不应把这种等待复制到响应式业务链中。
