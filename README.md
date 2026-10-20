# json-facade

A small facade for **manual JSON operations**. Application code depends on neutral JSON types and a two-method Provider SPI, rather than a particular Jackson version. HTTP codecs and framework JSON configuration stay with their official integrations.

Requires Java 17+. Current development version: `1.0.0-SNAPSHOT` (not yet published to Maven Central).

## Modules

| Artifact | Responsibility | Runtime dependencies |
| --- | --- | --- |
| `json-facade-api` | Binding, generic types, trees, token cursors and incremental SPI | JDK only |
| `json-facade-jackson2` | Jackson 2 Provider | API, Jackson 2 |
| `json-facade-jackson3` | Jackson 3 Provider | API, Jackson 3 |
| `json-facade-reactor` | Reactive byte[] / ByteBuffer operations | API, reactor-core |
| `json-facade-tck` | Reusable real-engine contract tests | API, JUnit (test utility) |
| `json-facade-coverage` | Aggregated execution data and coverage gate | Build only |

Add `org.jetlinks:json-facade-api` and one Provider to your application. Add `json-facade-reactor` only when reactive streams are needed. The library does not register Spring beans, replace HTTP codecs, or configure ORM mappers.

## Binding and generic types

```java
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonType;

JsonMapper mapper = Json.builder().provider("jackson2").build();
String json = mapper.write(java.util.Map.of("count", 3));
var values = mapper.read("[{\"count\":3}]",
    new JsonType<java.util.List<java.util.Map<String, Integer>>>() { });
```

`JsonType.of`, `listOf` and `mapOf` also construct generic types. Explicit `provider(new Jackson2Provider())` avoids ServiceLoader when class loading must be controlled. With discovery, exactly one matching Provider is required. Multiple Providers without a selection, duplicate IDs or no candidates fail before use; parse errors never trigger fallback to another engine.

Built mappers are immutable and safe to share. Construct configuration before publishing the instance. Each reader, writer and incremental session has its own state. Supplied InputStream/Reader/OutputStream/Writer remain caller-owned.

## Reactive streams

```java
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.reactor.JsonStreamMapper;

JsonStreamMapper streams = JsonStreamMapper.of(mapper);
// chunks: Publisher<byte[]>; buffers: Publisher<? extends ByteBuffer>
var items = streams.readBytes(chunks, JsonType.of(Item.class),
    JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS));
var fromBuffers = streams.readBuffers(buffers, JsonType.of(Item.class),
    JsonReadOptions.of(JsonReadOptions.Mode.NDJSON));
var output = streams.writeBytes(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY));
var outputBuffers = streams.writeBuffers(items,
    JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON));
```

Read modes: `SINGLE_VALUE`, `ARRAY_ELEMENTS`, `NDJSON`, `ROOT_VALUES`. A JSON Pointer selection can extract a value or array elements from a single wrapping document, skipping unrelated subtrees without materializing them.

Byte arrays are owned immutable chunks after publication. ByteBuffer input is copied at the adapter's input `onNext`, preserving position/limit/mark; heap, direct, read-only and sliced buffers are supported. Source storage can be reused once that call returns. ByteBuffer outputs are read-only, have independent cursors, and require no release.

The adapter honors demand, preserves Reactor Context, and uses no internal subscription, blocking I/O or forced Scheduler. Each subscription gets a fresh parser; cancelling closes it and cancels its source. Total memory includes the current source chunk, a bounded materialized record and the engine's constrained token buffers. A source must still supply reasonably sized chunks. Serialization buffers owned segments for one bounded value, using a reusable native generator. Huge documents can stream their structure without a whole-document tree; each scalar/bound value remains constrained.

Reactor cannot emit Java null. Null records fail by default, may be explicitly skipped, or can be retained as the non-null `JsonValue.NULL` node. Empty array output is `[]`; empty NDJSON output has zero bytes. An error never appends a successful closing bracket.

## Trees and structured documents

`JsonMapper.createObject()` / `createArray()` return the neutral `JsonObject` / `JsonArray` interfaces. Map/Collection initializers and mutations copy nested JSON containers; object keys are Strings. `toJavaValue()` exports an independent JDK snapshot. Use `toTree(bean)` for configured POJO mapping.

For a document with metadata and a streamed array, compose structural events:

```java
import org.jetlinks.json.JsonEvent;
import reactor.core.publisher.Flux;

Flux<JsonEvent> document = Flux.<JsonEvent>concat(
    Flux.just(JsonEvent.START_OBJECT,
        JsonEvent.name("meta"), JsonEvent.value(java.util.Map.of("version", 1)),
        JsonEvent.name("items"), JsonEvent.START_ARRAY),
    items.map(JsonEvent::value),
    Flux.just(JsonEvent.END_ARRAY,
        JsonEvent.name("summary"), JsonEvent.value(java.util.Map.of("complete", true)),
        JsonEvent.END_OBJECT));
var documentBytes = streams.writeEvents(document,
    JsonWriteOptions.of(JsonWriteOptions.Format.SINGLE_VALUE));
var inputEvents = streams.readEvents(chunks);
```

Event reads preserve structure without collecting containers. Event writes validate balanced containers and field/value order; numeric tokens retain text without a double conversion. Both sides honor demand and cancellation. Root-array record APIs and Pointer selection remain the convenient typed path. A bound POJO is one finite write unit; enormous aggregate objects should be expressed through structural events.

## Extensions

`JsonModule` registers exact-class `JsonAdapter<T>` instances through `JsonModuleContext`. Adapters read/write borrowed neutral cursors using the same binding context. Registrations are build-time only and must be thread-safe; duplicate module IDs or duplicate class adapters fail. Null values follow mapper policies rather than invoking adapters.

`configureNative` provides typed access to the selected Provider's native builder, for engine-specific modules, mixins and naming policies:

```java
JsonMapper mapper = Json.builder().provider("jackson2")
    .configureNative(com.fasterxml.jackson.databind.json.JsonMapper.Builder.class,
        nativeBuilder -> nativeBuilder.propertyNamingStrategy(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE))
    .build();
```

For Jackson 3, use `tools.jackson.databind.json.JsonMapper.Builder.class`. Each build has a fresh native builder; callbacks run in order after all types have been checked. A mismatched type or a conflict with shared `JsonOptions` policies fails at build time. Native callbacks belong in application assembly code and must not retain the builder or mutate a published mapper. Native annotations and native configuration remain explicit migration boundaries.

Opt-in [large-document stress and allocation benchmarks](docs/contracts.md#implementation-and-validation) exercise both engines under bounded heaps.

The engine SPI has only `JsonProvider.id()` and `create(JsonConfiguration)`. No Provider-specific reactive SPI or capability registry is required. [The contracts](docs/contracts.md) specify framing, ownership and extension behavior.

## Build and quality gates

```sh
mvn -B -ntp verify
```

Requires Python 3 for the aggregate coverage gate. The build runs the shared TCK against both actual engines, Reactor demand/cancellation tests, compilation with all warnings treated as errors, Checkstyle, SpotBugs at maximum effort/low threshold, and JaCoCo. **Every production module must have at least 80% branch coverage**, including execution from cross-module tests. No production classes/branches are excluded. JUnit/TCK utilities are not production code in the aggregate report.

Coverage HTML: `json-facade-coverage/target/site/jacoco-aggregate/index.html`. CI runs the same gates on Java 17 and 21 and retains reports. These checks are concrete quality requirements; they do not claim a third-party “A+” certification.
