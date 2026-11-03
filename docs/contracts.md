# Public contracts

For installation and examples, see the [Chinese user guide](user-guide.md).
This document describes the current implementation and its verification boundaries.

## Collection API and migration

`JsonObject` is a `Map<String, Object>` and `JsonArray`
a `List<Object>`. Collection reads/writes follow JDK contracts; `node(name/index)`
retains path-aware missing/null access. Insertions retain references; independent
copying remains explicit in factories, `deepCopy` and `toJavaValue`. Typed container
access returns Java null for missing/null. Borrowed views remain recursively
read-only through collection methods and views. An optional deprecated migration
module provides `com.alibaba.fastjson.JSON/JSONObject/JSONArray` without a fastjson
dependency; engine binding remains with `JsonMapper`. Compatibility reads lazily
adapt nested containers, while mutations return the original previous value.
Existing imports remain valid for the supported source API. The module replaces
dependencies providing the same fastjson 1.x classes; full binary compatibility
is not guaranteed. Native annotations, Feature flags and deprecated container types
nested in arbitrary DTO/generic declarations are outside its binding contract.

## Fastjson2 support

`json-facade-fastjson2` supplies Provider ID `fastjson2`, using fastjson2 2.0.66
and the API only. It supports synchronous binding/generics, neutral trees,
native extensions and prepared numeric/null/pretty flags. Native
`ObjectReaderProvider`/`ObjectWriterProvider` instances are private to each
prepared mapper; no global registration, tokenizer copy, hidden Jackson
dependency or capability registry is introduced.

Construction requires explicit `Fastjson2Provider.nativeOptions()`, which sets
string/number limits to `Integer.MAX_VALUE`; native limits and available memory
then apply. Finite token limits, strict duplicate keys and strict unknown fields
are rejected at preparation, including native callback conflicts. In native
2.0.66 probes, duplicate-key checks did not reject duplicate DTO fields and the
unknown-field flag did not reject an extra record property. The facade does not
replace those paths with Map prebinding, graph scans or slower binding algorithms.
Native depth limits and neutral-tree structural-copy limits remain configured.

`Fastjson2Builder` exposes native reader/writer contexts for build-time
extensions. Operation transformations accept `JSONReader.Context` and
`JSONWriter.Context`; they cannot replace the configured native Providers or
conflict with common policy. Fresh native Providers isolate prepared variants,
tree ownership and caches. Build callbacks and accumulated operation transforms
may replay during preparation, including preparation of a compact writer for a
pretty mapper; they must have no external side effects. Reads/writes do not
reconfigure shared contexts. Reuse prepared mappers to amortize cache warmup.

Conversion reuses configured native JSON UTF-8 encoding/decoding, with a
whole-value byte buffer and no intermediate String or neutral tree. It preserves
the JSON representation of dates, binary values and custom native handlers. Object
references follow native `ReferenceDetection`/`$ref` behavior and operation-local
native resolve tasks. The facade adds no reference representation or ID table.

Direct binding to `JsonValue`, `JsonObject` or `JsonArray`, including nested DTO
properties and generic elements, requires native `DisableReferenceDetect`.
Such trees preserve `$ref` as ordinary JSON data. Otherwise the tree reader fails
instead of copying containers before native deferred reference resolution and
silently losing fields. To resolve references before copying a neutral tree, use
`readTree(String)`; shared acyclic children become independent copies and cycles
fail with `LIMIT`. The native API supplies no post-resolution tree-binding hook;
the facade adds no second binding pipeline or reference tracker. This restriction
does not change ordinary DTO/Map/List reference resolution.
The check runs when the native tree reader is invoked; stream input may already
have been consumed at that point.

Fastjson2's `JSONReader.of(InputStream, ...)` consumes the complete input before
binding, and encoding buffers one complete value before writing it. Its
`JSONStreamReader` and delimiter-consumer APIs support blocking record streams,
but the inspected public APIs use a separate default context and do not expose
the facade's prepared Providers or arbitrary-chunk nonblocking parsing. Therefore
facade cursors, array iterators, structural writers and incremental/event sessions
fail with `CONFIGURATION` before I/O; no stream is collected behind those APIs.
Neutral `JsonModule` and operation attributes are rejected because a native reader
context attachment is unavailable. Applications may explicitly use native line
APIs outside the facade, with their own configuration and lifecycle.

Whole-root checks require a value and native EOF, rejecting additional roots.
Caller I/O remains open. Native grammar/coercion remain fastjson2 behavior,
including single quotes and native-accepted trailing commas; permissive syntax
is not revalidated by a second parser. Generic native
`JSONException` failures have no structured category/path, so they map to
`BINDING` without parsing error messages; native I/O causes map to `IO`. Facade
root checks use `SYNTAX`, and neutral structural-copy limits use `LIMIT`.

## Responsibility and configuration

The facade serves manual JSON operations. HTTP codecs, ORM configuration, network
framing and application transactions remain host responsibilities. Java 17 is the
minimum; the API has only JDK dependencies. Providers, Reactor and transports are
separate modules.

| Layer | Responsibility |
| --- | --- |
| Provider | Native tokenization, binding, configured extensions and native resource lifecycle |
| Shared API | Neutral types, tree semantics, framing, selection and value budgets |
| Reactor | Demand, cancellation and safe coordination of operation-local resources |
| Netty / Spring buffers | Input leases and output ownership, using the shared stream implementation |

`JsonProvider` exposes only `id()` and `create(JsonConfiguration)`. Discovery
requires one unambiguous match; duplicate IDs, missing candidates and ambiguity
fail before use. Parse failures never cause engine fallback. Each Provider
documents its supported operations; unsupported configuration fails at preparation
and unsupported operations fail with CONFIGURATION before I/O. A supported
incremental operation may not collect the complete stream as a substitute.

Built mappers are immutable and shareable. Builders, mutable trees, cursors and
sessions are operation-local. The library creates no background threads, global
live queues, separate buffer pools, MBeans or per-record tracing.

`JsonOptions` is an immutable record. Its nine `withX` methods preserve other
fields and reuse canonical validation. Read/write option copies do the same.
`selectValue/selectElements` create SINGLE_VALUE options with the default FAIL
null policy and record budget. Class overloads delegate once to the existing
JsonType entry point; they do not introduce per-record type conversion or a cache.

`configureNative` callbacks borrow a fresh typed builder per build. All callback
types are checked before any callback runs; callbacks then run in registration
order. Type mismatches and conflicts with authoritative `JsonOptions` fail at
build time. Callbacks must not retain builders or mutate published mappers.
Native annotations, modules and custom handlers remain explicit engine boundaries.

### Class-name type discovery

By default, JSON-supplied class names (for example `{"@type":"org.example.Type"}`)
do not choose arbitrary runtime classes. Ordinary target types, field annotations,
reference handling and DTO-declared logical-name subtype mappings remain supported.
This uses native configuration, not a JSON field scanner or replacement resolver.

Fastjson2 explicitly disables provider AutoType, `SupportAutoType` and
`SupportClassForName`, and removes the inherited provider AutoTypeBeforeHandler.
JVM global flags/handlers are not modified.
The native `checkAutoType` extension enforces the provider disable flag before
delegation, covering native Object reads that otherwise consult JVM accept lists.
No native resolver or allow-list implementation is copied.
Jackson 2/3 explicitly deactivate
default typing and install a native validator that denies class-name polymorphism
before resolving/loading a subtype;
this also rejects annotation-driven CLASS/MINIMAL_CLASS IDs. NAME IDs remain native.

Native opt-ins emit a WARNING through `System.Logger` under the Provider class
name during construction/preparation, with no input contents or per-value logging.
Fastjson2 diagnoses provider re-enablement, either reader feature and context/provider
before-handlers; its internal compact variant does not duplicate the warning.
Jackson diagnoses default typing or replacement of the facade's validator at
construction and preparation. Even a restrictive replacement is an explicit
policy override and is reported. An unchanged empty operation does not prepare or log.
An operation reader must retain its mapper's default typer and validator: native
root-deserializer caches are shared by type and cannot isolate a replacement
policy. Cross-policy reader configurations fail before the preparation probe can
populate shared caches. Change these policies through build-time `configureNative`.

Jackson 2 adaptation secures a default native mapper without changing its source;
explicit default typing/custom validators are preserved and warned. Custom native
readers, adapters and annotation handlers remain trusted extensions: configuration
diagnostics do not inspect arbitrary user code or scan all DTO metadata. Validators
and handlers must constrain permitted types; warnings do not provide that validation.

### Operation configuration

`JsonMapper.withOptions(JsonOperationOptions)` prepares an
immutable, reusable operation mapper. Unspecified common flags inherit the current
mapper; explicit false differs from inheritance. Empty options return the same
instance. Options cover binding flags, typed application attributes and typed
native reader/writer transformations, not framing, Pointer selection or budgets.

Preparation applies common overrides, then native transformations, then validates
explicit flags and mandatory resource/root contracts before any I/O. Native
transformations run once per preparation and must return a non-null reader/writer;
they cannot replace the stream factory. Attributes are passed through native
binding contexts and neutral adapter cursors; values must be stable and thread-safe.
They only have an effect when a registered adapter or native handler consumes them.
Jackson `ObjectReader.at(...)` filters are rejected during preparation: their
filtered EOF can hide trailing roots. Validation initializes a native iterator on
empty input, inspects its public parser and closes it without binding a value.
Normal reads incur no additional parsing or checks. Use facade Pointer selection
through `JsonReadOptions` when selecting nested input.

Providers share native caches for lightweight changes. Changing default null
inclusion uses native copy/rebuild with isolated serializer caches during
preparation; annotations and custom serializers retain their native precedence.
No global options cache or ThreadLocal is introduced. Prepared mappers propagate
through synchronous, tree, iterator and incremental operations; reactive resources
remain subscription-local and streaming output remains compact.
Conversion connects the configured native reader and writer through native token
buffers; Jackson 3 uses ObjectWriter.writeValueIntoBuffer directly, preserving
the generator's binding context without an intermediate neutral tree.

Contract tests cover default/explicit-false inheritance, warm-cache null isolation,
nested attributes and native overrides, conversion/tree/session consistency,
concurrent variants, early conflict rejection, caller I/O ownership and compact
reactive output. Reconfiguration costs belong to preparation, including a native
stream-factory copy when Jackson 2's duplicate-key detection changes. This avoids
changing detectors after parsing contexts have been created.

Modules and adapters are registered at build time and must be thread-safe.
Module IDs and adapter classes must be unique. Adapter matching is exact Class,
without subtype widening or generic-Type registration. Borrowed adapter cursors
must neither be closed nor retained; each callback consumes or writes one complete
value. Java null follows mapper policy without invoking an adapter.

## Binding, types and I/O

`JsonType` captures JDK Type, including nested generics. Anonymous subclasses
must capture concrete arguments directly. Class inputs are convenience forms;
there is no declared-type `write(value, JsonType)` overload.

Synchronous `read` requires one complete root and rejects trailing nonwhitespace
content. Empty input differs from JSON null. `convert` uses configured JSON
binding, not identity-preserving bean copying. Native coercion, annotations,
polymorphism, dates and target numeric precision remain Provider behavior.

Byte APIs use UTF-8. Jackson 3 delegates byte[] reads and byte output directly to
the configured native mapper. Its String output decodes that UTF-8 byte output,
preserving the same escaping representation. Native Writer output may spell
Unicode differently. Jackson 2 retains stream-based convenience paths to
preserve configured IO decoration and original IO error causes.

Caller-supplied InputStream, Reader, OutputStream and Writer remain caller-owned.
Closing a cursor releases its native state without closing those objects or
repairing incomplete containers. Reader `readValue/skipValue` consume the whole
current value; the following `next()` returns its successor. Retained closed
wrappers must detach native contexts and borrowed input/output references,
including when cleanup fails.
Each standalone cursor `readValue` creates a native per-value binding context;
object IDs can refer within that value but not to earlier/later calls. Borrowed
readers inside Adapters retain the enclosing native context and its object IDs.
Jackson cursors retain only the most recent JDK Type and immutable typed reader,
skipping repeated generic resolution when that same Type is reused. Changing the
type delegates to the native reader again. Closing detaches both references;
binding contexts and object IDs are never cached across values.

## Synchronous array iteration

`iterateArray(InputStream, Class/JsonType)` returns
a JDK-only `CloseableIterator<T>` over root-array elements, implemented through the
existing JsonReader. Creation is lazy; hasNext advances at most to the next element
token without binding it, while next binds only that value. JSON null is a value.
Repeated hasNext calls must not advance, and exhaustion uses standard Iterator
semantics. Fully consuming validates the closing array and rejects trailing content.

Close, exhaustion and parser/binding failure detach and release cursor state without
closing caller-owned input. Early close validates only the consumed prefix. The
operation is synchronous, not thread-safe, and inherits native token/depth limits;
it does not add incremental-session record byte budgets or collect the document.
No parser fork, Provider-specific iterator or reactive bridge is introduced.
Cursor binding normalizes errors directly at the native read call, avoiding a
captured action per value when iterator call depth prevents JIT elimination.
Binding and failure normalization use the Provider's public APIs.
Object ID scope is one element, matching incremental sessions and reactive
records. Bind the complete array in one operation when its elements share IDs;
native annotations, mixins and resolvers define the reference representation.

Real-engine TCK covers both Providers, configured adapters/generics,
null/empty/malformed/trailing input, lazy binding and IO ownership. API tests cover
foreign-cursor cleanup failures and exception identity; the iterator has full
line/branch coverage. Each JDK
and Provider also consumes 67,110,919 generated bytes (11,185,153 records) under a
32 MiB heap, then retains 192 closed iterators whose former inputs total 192 MiB,
without explicit GC. Native read-ahead remains possible; no records are collected.

## Trees, locations and validation

`JsonValue`, `JsonObject` and `JsonArray` are neutral interfaces; current mapper
factories use JDK storage. Structural factories accept acyclic JSON-compatible
scalars, String-keyed Maps, Collections and Java arrays. Arbitrary POJOs use
configured `toTree`. Structural byte[] becomes an array; native binding can
encode it as Base64.
`JsonObject` extends `Map<String, Object>` and `JsonArray` extends `List<Object>`.
Collection `get` returns raw values; Map writes return previous values and List
writes follow JDK signatures, bounds, equality and view semantics. Insertions retain
references and do not scan or copy containers; supplied immutable children stay immutable.
Values intended for structural
copying must satisfy the structural JSON data contract; arbitrary POJOs use Provider
binding. Null keys are rejected by object insertion.

`node(name/index)` provides path-aware JsonValue access independently of collection
get: missing and JSON null are distinct non-null nodes. Typed `getObject/getArray`
return Java null for missing/null and reject actual shape mismatches. `asObject`
and `asArray` require actual matching containers. JsonValue exposes no mutators;
mutations use collection interfaces. Plain JSON null values use Java null in collections.

Factory initializers, `deepCopy` and `toJavaValue` recursively copy containers.
Copy depth uses the owning mapper's maxDepth, or the default for unbound nodes;
cycles and excess depth fail with LIMIT without an identity table or graph pre-scan.
Shared acyclic children are copied independently. Structural numbers accept exactly
Byte/Short/Integer/Long/Float/Double/BigInteger/BigDecimal. Other Number classes or
POJOs require configured binding before structural copying. `JsonValue.of(existingNode)`
retains node identity. Binding results are copied because native handlers may return
shared containers. Synchronous native binding borrows the built-in backing graph;
internal transparent proxies reuse their existing neutral node. Other custom
collection nodes use ordinary JDK views instead of a whole-tree snapshot.

`wrap(Map/List)` constructs an O(1) recursively read-only borrowed collection view.
Nested containers are wrapped lazily through raw get, node, entrySet, values, iterators,
toArray and subList. JDK unmodifiable delegates reject all mutation paths and mutation
callbacks. Source changes remain observable. Sources must contain String-keyed Map,
List and structural scalars, be stable during use and acyclic for JSON processing.
Unsupported leaves fail on exposure; wrapping does not validate the entire graph.
Copies/exports are independent and mutable. Cached child views retain their original
container after ancestor replacement; no view adds synchronization.

Node scalar defaults apply only to missing/null input. Present conversions use the
configured Provider and propagate failures. Static structural nodes have no mapper
and require an explicit mapper for present-value conversion; there is no global fallback.
Nested node views preserve destination mapper and path. Copies restart at the root.
Paths retain segments, never ancestor data; RFC 6901 pointers traverse storage in O(d)
and create only the final node (plus index metadata for mixed paths).

`requirePresent`, `requireNonNull` and predicate `require` return the current node
on success. Exception factories receive immutable JsonFailure and run only on failure.
Default failures use VALIDATION; Provider binding paths are prefixed with the node path.
Map/List equals/hashCode are structural; mutable collections are not stable hash keys.
Large documents should use cursors/events rather than materializing entire trees.

## Deprecated source migration module

`json-facade-compat-fastjson` depends only on the API at runtime. Its deprecated
`com.alibaba.fastjson.JSON/JSONObject/JSONArray` classes provide common
parse/write/DTO, constructor, scalar-getter and Map/List operations. Existing imports
and supported calls stay unchanged; the classes are directly assignable to
JsonObject/JsonArray. Replace or exclude `com.alibaba:fastjson` and any other jar
providing the same classes before adding this module. The native fastjson2 Provider
uses a different package and can coexist. Full third-party binary compatibility
is not promised; unsupported API usages still need migration.

Default static helpers/constructors use Json.global; explicit-mapper roots support
multiple configurations. Map/List constructors share caller storage. Nested get,
iteration and stream lazily expose legacy container views over the same storage;
repeated wrappers have no identity guarantee unless an already-compatible object was
inserted. Mutations retain references and return the original previous value without
allocating a compatibility view. deepCopy/toJavaValue remain explicit snapshots.
Serialization borrows the existing neutral storage rather than walking compatibility
getters to create more wrappers. Native binding and syntax stay with the selected Provider; no parser, reflective DTO
binder, global native registry or JSON string roundtrip is added. Generic DTO/JDK
binding uses JsonType; arbitrary nested declarations of the deprecated classes have
no cross-provider binding guarantee. Prefer the neutral interfaces for DTO properties.

### Common fastjson source compatibility

The compatibility module keeps the original com.alibaba.fastjson imports. JSON is
the shared container base, so JSON assignments and inherited static calls such as
JSONObject.toJSONString(bean) compile. JSONAware and JSONStreamAware are supported.
All declared legacy APIs and signature types carry method-level @Deprecated with
migration guidance; standard Map/List and neutral JsonValue methods remain unmarked.

| Area | Supported behavior | Migration target / limits |
| --- | --- | --- |
| Parsing | String, UTF-8 byte[] and InputStream; Class, Type, direct TypeReference capture and JsonType targets | JsonMapper.read / JsonType; null/blank String returns null; one complete root is required |
| Output | Static/inherited/instance toJSONString, pretty boolean, UTF-8 bytes, Writer, OutputStream byte count, instance Appendable | JsonMapper.write/writeBytes; caller owns streams; Writer/OutputStream bind directly without a JSON string roundtrip; other Appendable uses a complete String |
| Options | PrettyFormat → pretty(true); WriteMapNullValue → omitNulls(false); UseBigDecimal → losslessNumbers(true); IgnoreNotMatch → failUnknownProperties(false) | JsonOperationOptions; explicit options preserve Provider-specific annotation precedence; omitted options inherit the configured mapper |
| Containers | Capacity/ordered constructors, standard collections, fluent helpers, shallow clone, scalar/date/binary getters, Type/TypeReference member binding, getInnerMap shared neutral view | JsonObject/JsonArray; Provider conversion rules apply; deepCopy is the explicit isolated copy |
| Root identity and bounds | toJSON(existing JSON) returns the same instance; legacy array getters check List bounds | Neutral node(index) still returns a missing node for invalid indices |
| Failures | Legacy binding/I/O failures become JSONException preserving the original JsonException cause; unsupported options/APIs throw UnsupportedOperationException | Neutral node/as operations retain JsonException |

The adapter validates the complete explicit feature set or bit mask before mapper
discovery, operation configuration, binding or I/O. It names an unsupported option
and its replacement rather than ignoring it. Only the four mappings above execute.
Class-name discovery features (including SupportAutoType and WriteClassName),
filter/config-registry entry overloads, date-format entry overloads and global type
key mutation are explicitly rejected. ParserConfig/SerializeConfig exist only for
selected signatures; construction/global lookup throws. Filter interfaces are
signature markers and are never executed. TypeReference(Type...) and uncaptured
types fail at getType, before binding; no native type-substitution cache is added.

JSONPath, old parser/serializer internals, mutable global flags, mix-in registries,
fastjson annotation execution and permissive coercion emulation are not supplied.
APIs outside the common signatures remain compile-time unsupported. This is gradual
source migration, not the full fastjson binary ABI or an identical default policy.
For example, old fastjson omits null object values by default, while this module
inherits the configured JsonMapper policy; set omitNulls on that mapper if needed.
The class-name discovery prohibition continues to apply. @Deprecated signals
migration, not a runtime restriction.

Binding remains Provider-owned. Arbitrary DTO properties or generic declarations
containing deprecated container types have no cross-Provider guarantee; use neutral
interfaces or JDK Map/List in those declarations. Root legacy containers are adapted
only at the compatibility entry boundary. No parser, serializer, DTO graph scanner,
native registry or global mapper-variant cache is added. Ordinary get/put retain
their direct storage path; iterators and sublists use JDK storage views and only
wrap nested legacy containers when accessed.

## Default, named and Spring mappers

`Json.global()` and `defaultMapper()` return the same default. First access may
discover one Provider, or the host may register a mapper before first use.
Named configurations are independent, case-sensitive and write-once. Unknown
names and duplicate registrations fail; names are nonblank without surrounding
whitespace. There is no fallback, reset, unregister or replacement.

Global references live with the API ClassLoader. Names must be a finite set of
application configurations. Unloadable plugins and repeated isolated contexts
should use host-owned instances. Lazy factory reservation does not run the
factory at registration; failures may retry that same factory, but its reservation
cannot be replaced. First lookups resolve outside the publication lock and may
invoke the factory concurrently. Factories must support repeatable, thread-safe
resolution, without relying on one invocation for side effects/resource ownership.
Exactly one result is published; successful callers all return that winner.
Unregistered default discovery can also construct concurrent candidates.
Same-thread recursive initialization fails. Registration during active resolution
fails immediately. A failed call propagates its original failure, even if another
caller publishes; failed unreserved discovery does not publish a partial instance.
The published lookup path is a volatile read and never invokes a factory or waits
for another resolver. Container factories delegate singleton creation to their owner.

The optional Spring module supports Spring 6 / Boot 3 Jackson 2 configuration.
It adapts the unique or primary native bean, unless a host JsonMapper bean takes
precedence. Plain Spring imports `JsonFacadeConfiguration`; ordinary mapper
configuration can load without Boot, Reactor or Netty. No Boot 4 bridge is supplied.

`Jackson2Provider.adapt` uses native `copy()`, preserving modules, mixins,
coercion, dates, naming and full inclusion rules. The source is unchanged and
later changes are not tracked. Custom handlers retain Jackson's sharing and
thread-safety semantics. Non-JSON factories and copy-unsupported subclasses fail.
The snapshot installs tree support, enforces single-root and caller-owned IO
contracts, disables container repair/source-in-location and aligns read/write
depth to their minimum. Other native read constraints remain. The reported
`omitNulls` flag summarizes recognized global inclusion, not all native filters.

Spring reserves the default before ordinary business-bean initialization and
resolves the fully initialized facade bean. Native mapper/customizer/facade
construction must not depend on that same global. Earlier bean-factory callbacks
cannot rely on this registration. Global facade beans must be singletons.
Existing global reservations or initialized defaults fail startup.

`json.facade.global-enabled=false` leaves only injectable context ownership;
`json.facade.enabled=false` disables Boot auto-configuration.
`json.facade.max-value-bytes` defaults to 8 MiB for the adapted mapper.
Context close never resets globals. Successful publication drops the global
factory reference; the context-owned bootstrap keeps resolution available to
already active callers until destroy clears its BeanFactory reference. Failed
startup releases the dead container but leaves its global reservation. Native handlers may
retain container dependencies, so global snapshots are not unloadable contexts.

## Incremental reads and structural events

| Read mode | Contract |
| --- | --- |
| SINGLE_VALUE | One complete root; empty input fails |
| ARRAY_ELEMENTS | Direct root-array elements; empty array completes, empty input fails |
| NDJSON | One value per nonblank LF/CRLF line; no bare CR or multiline record; final LF optional |
| ROOT_VALUES | Whitespace-separated roots; empty input completes |

Selection is available only in SINGLE_VALUE. It binds one pointed value or the
direct elements of a pointed array, skipping unrelated subtrees without materializing
them. Missing, wrong-shaped or repeated selected paths fail; an empty selected
array completes. Repeated selected paths fail even with duplicate-key detection
disabled. To validate the complete document, consume it to completion.

Sessions borrow the remaining ByteBuffer region without changing its cursor.
Feed only when `needsInput()`; keep storage stable until it is requested again
or the session closes. Call `endInput()` once at real EOF and drain to END.
VALUE may contain Java null; NEED_INPUT and END are separate states.
The receiver overload synchronously invokes its receiver exactly once on a
successful read; exceptions propagate and require closing the failed operation.
Receivers must not block or reenter. Current Providers deliver directly through
this generic API; independent Providers may use its default pull bridge.

Native nonblocking parsers own tokenization. Record binding buffers only the
current record's native tokens; arbitrary native deserializers cannot pause
mid-call. Event reads deliver one whole SINGLE_VALUE document without collecting
containers. Cancellation validates only the consumed prefix and does not roll
back already completed downstream effects.

Events and their payload references are immutable; callers must keep values
stable during use. Container/boolean/null constants are reusable. Direct scalar
events bypass Java scalar adapters; `JsonEvent.value` uses configured binding.
Numeric text is validated in O(n) time/O(1) space without double conversion or raw
JSON injection. Event copying preserves structure and tokenizer scalar text,
not source whitespace/escaping; native tokenizers normalize integer `-0` to `0`.

## Reactor, buffers and lifecycle

Each subscription owns fresh operation state, preserves Reactor Context and
propagates upstream errors unchanged. No internal subscription, blocking IO,
forced Scheduler or whole-stream collection is introduced. Reactive Java null
fails by default, may be skipped explicitly, or is represented by JsonValue.

| Memory | Ownership |
| --- | --- |
| Input byte[] | Immutable after publication; storage cannot be reused after onNext |
| Input ByteBuffer | Copied immediately in the adapter's onNext; cursor preserved |
| Input transport lease | Transferred until views are consumed or parser cleanup finishes |
| Output byte[] / ByteBuffer | Independent storage transferred downstream; ByteBuffer is read-only with an independent cursor |
| Netty read/readEvents | Borrow input, retain one reference during delivery; source retains its own responsibility |
| Netty readOwned/readOwnedEvents | Consume one transferred reference without another retain |
| Spring DataBuffer input | Consume one transferred ownership without another retain |
| Netty / DataBuffer output | Transfer ownership downstream; adapter releases unpublished segments |

Async queues before adapter delivery remain the host's responsibility. Read-only
views do not make shared bytes immutable. Netty preserves indices and traverses
composite NIO views without consolidation; unsupported NIO access fails.
Spring keeps `readableByteBuffers()` iterators open while views are borrowed,
then closes the iterator before releasing input, even if iterator close fails.
Active native/user callbacks must return before parser close and input release.

Output uses a reusable compact native generator per subscription and fresh
binding contexts per record, preventing object-ID accumulation. Serial progression
uses zero prefetch. Jackson compact writers disable native flush-after-value;
the caller controls flush boundaries, and the reactive adapter still flushes
each record/event before delivery. Full native buffers may write earlier. ARRAY
emits `[]` for an empty source; NDJSON/ROOT_VALUES emit
zero bytes and separate records with LF. SINGLE_VALUE requires exactly one value.
Event output accepts only SINGLE_VALUE and validates balanced containers,
field/value ordering and root uniqueness. Errors/cancellation never repair output.

One finite bound value is synchronously encoded into owned segments before
delivery; demand cannot suspend an arbitrary serializer at a byte boundary.
Native callback cancellation records termination and cleans up after callback
return. Downstream owns accepted segments and any queues/discards it adds.
Reactor Netty send and Spring response writeWith own buffers they accept.
Native generator-to-segment copying remains; this is not end-to-end zero-copy.

## Limits and performance boundaries

Effective record bytes are the minimum of mapper and operation budgets. Both
default independently to 8 MiB. Output chunk size defaults to 8192; framing bytes
are accounted separately from values. These limits do not bound synchronous
whole-value operations, total stream bytes, source chunk size, bound Java-object
overhead, concurrency or RSS.

Token length, depth, current record, output segments, host concurrency, cumulative
bytes and time require separate budgets. Duplicate-key detection retains all
seen names in each open object; a huge object can consume substantial memory even
in event mode. Large aggregate documents should stream bounded records or structure;
a huge individual POJO/string/number is still one materialization unit.

Native-reference benchmarks must state equivalent configuration and output
ownership. A native loop without demand, cancellation, budgets and framing is
not a measure of wholly removable facade overhead. Short-record reactive writes
and token delivery remain meaningful costs. Avoid extra tree/export/conversion
steps, share mappers/types, and measure actual workloads before changing transports.

## Implementation and validation

Java 17.0.18 and 21.0.10 each pass 348 tests with no failures, errors or skips.
Compilation uses all warnings as errors; Checkstyle, SpotBugs and every production
module's 80% branch-coverage gate pass. Aggregate branch coverage is 1553/1653
(93.95%); compatibility covers 154/156 branches (98.72%) and fastjson2 covers 97/98
(98.98%). Tests cover all three Providers, configured
extensions, owned/borrowed tree isolation/locations, IO failures, demand, cancellation and transport
ownership. The [CI matrix](../.github/workflows/verify.yml) also runs small-chunk,
closed-owner, tree-retention and pooled-buffer regressions.
Compatibility regressions cover inherited/static/instance output, legacy interface
serialization context, generics, collection views, shallow clone, bounds/null
defaults, deprecation metadata and every unsupported enum option before I/O.
The same compatibility fixture also runs with each Provider as the global mapper;
native grammar/conversion differences remain Provider-owned.
To repeat the alternate-global runs, set JAVA_HOME to the desired JDK and run
the command below with json.compat.provider set to jackson2, jackson3 or fastjson2:

```sh
mvn -B -pl json-facade-compat-fastjson -am -Dtest=CompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false -Djson.compat.provider=fastjson2 test
```
Regressions also cover fastjson2 resolved/literal reference tree boundaries,
nested DTO/generic tree bindings, Jackson filtered-reader rejection and trailing
roots across all synchronous inputs. Preparation does not deserialize a probe
through business handlers. These checks do not change ordinary DTO read/write or
streaming hot paths; the corresponding performance results below remain applicable.
Type-discovery regressions cover inert class-name tags, CLASS/MINIMAL_CLASS denial
before subclass initialization, logical-name subtype compatibility, warning counts
and payload privacy, adapted/native overrides and cross-policy reader rejection.
An isolated fastjson2 JVM installs global accept lists/handlers and ClassForName,
then verifies that facade defaults remain closed without mutating those globals.
Reference regressions cover configurable structural-copy limits, shared acyclic
children, native object-ID mixins, per-record ID isolation and nested Adapter
context reuse. Four independent Java 17/21 and Provider runs each consume over
1 GiB and one million distinct IDs with record-local self-cycles under a 32 MiB heap. This
does not promise cross-record references or add a facade reference protocol.
Fastjson2 independently completes one million synchronous round trips per JDK
under a 32 MiB heap, alternating prepared variants and checking native self-cycle
identity, conversion and borrowed inputs. Its classpath contains only API and
fastjson2 modules plus the native engine; this is not a streaming-memory claim.

Large-document evidence covers at least 1 GiB aggregate documents under a 128 MiB
heap, with bounded records/tokens; pooled transport runs also constrain direct
memory and require zero pinned bytes at completion. These results do not establish
fixed memory for arbitrary individual values or production concurrency.

JMH covers stream sessions, events, tree access, configured native/facade reads,
independently owned writes and Netty transports. Current allocation evidence
supports fused Pointer access, direct receiver delivery and Jackson 3 native
byte[] delegation. The Class convenience comparison adds about 16–28 B per
one-record operation and about 16 B per 1000-record operation; no per-record type
wrapping was observed. Timing results do not establish universal CPU savings.

The Jackson compact-writer flush comparison uses Java 21, a 512 MiB heap,
GC profiling, two forks, three 500 ms warmups and four 500 ms measurements.
Each operation publishes the same 1000 short records with the same ownership,
budgets and record delivery boundaries. Removing native flush-after-value leaves
one explicit flush per record. Time is mean ± JMH error:

| Provider / format | Before µs | After µs | Before / after B per operation |
| --- | --- | --- | --- |
| Jackson 2 / NDJSON | 90.22 ± 18.59 | 97.62 ± 18.42 | 231633 / 231633 |
| Jackson 2 / ARRAY | 98.08 ± 11.72 | 79.31 ± 8.77 | 231777 / 231777 |
| Jackson 3 / NDJSON | 81.41 ± 9.26 | 73.37 ± 3.97 | 239729 / 239729 |
| Jackson 3 / ARRAY | 82.41 ± 13.38 | 72.15 ± 3.91 | 239873 / 239885 |

Allocations remain effectively unchanged and timing intervals overlap; the result
supports simpler flush ownership without establishing a universal CPU gain.

Fastjson2 JMH uses Java 21, a 512 MiB heap, GC profiling, two forks, three
300 ms warmups and four 300 ms measurements. Native/facade paths share depth,
number/null configuration, complete-root checks and independently owned byte[]
results, operating on one/1000 three-field Map records. Conversion uses the same
UTF-8 representation in both paths. Read rows are revalidated with class-name
discovery explicitly disabled in both implementations; unchanged write/conversion
paths reuse their baseline. Results are mean ± JMH error:

| Operation | Records | Native µs | Facade µs | Native / facade B per operation |
| --- | --- | --- | --- | --- |
| Read byte[] | 1 | 0.080 ± 0.004 | 0.080 ± 0.002 | 544 / 544 |
| Write byte[] | 1 | 0.054 ± 0.003 | 0.054 ± 0.002 | 152 / 176 |
| Convert | 1 | 0.143 ± 0.003 | 0.162 ± 0.012 | 696 / 744 |
| Read byte[] | 1000 | 75.68 ± 3.04 | 75.01 ± 4.34 | 372306 / 372306 |
| Write byte[] | 1000 | 42.20 ± 1.98 | 43.09 ± 5.50 | 66945 / 66945 |
| Convert | 1000 | 119.95 ± 10.77 | 117.30 ± 5.40 | 439251 / 439299 |

Read timing intervals overlap and normalized allocation matches native;
configuration checks/warnings introduce no added allocation per record in these
fixtures. JIT optimization can affect whether a small operation wrapper survives.
These results do not prove negligible CPU overhead for arbitrary bindings, streams
or trees. Reuse prepared mappers and account for whole-value buffering on
synchronous streams and conversion.

Operation-options JMH uses Java 21, a 512 MiB heap, GC profiling, two forks,
three 300 ms warmups and four 300 ms measurements. It compares the same one/1000
three-field Map records with a default mapper, one unused typed attribute and
an explicitly prepared null-inclusion variant. Configuration does not change
payload bytes in this fixture; behavior differences are verified separately.

| Provider | Attribute preparation µs / B | Null-policy preparation µs / B |
| --- | --- | --- |
| Jackson 2 | 0.284 / 2592 | 0.633 / 8648 |
| Jackson 3 | 0.315 / 2728 | 1.284 / 12208 |

Empty preparation returns the same mapper with no measured allocation. Native
serializer cold-start work is outside these preparation timings. Reusing an
attribute operation adds about 64 B (Jackson 2) / 56 B (Jackson 3) per complete
conversion, independent of one versus 1000 records; complete reads add roughly
16–64 B. Cursor, incremental-session and reactive-write comparisons do not show
an added allocation per record from options. At 1000 records, default/attribute
reactive writes allocate 250258/250258 B (Jackson 2) and 258354/258354 B
(Jackson 3). Their measured times overlap; there is no stable slowdown signal.

Default-path before/after comparisons against fe9018b retain equivalent
configured native/facade input and independently owned stream-output workloads.
Repeated Jackson 3 byte reads and both Providers' stream writes show overlapping
timing intervals and no material allocation growth. These measurements support
reusing prepared options; they do not establish zero framework overhead for
arbitrary handlers, trees or workloads.

Borrowed-tree JMH uses Java 21, a 512 MiB heap, GC profiling, two forks, three
300 ms warmups and four 300 ms measurements. Across both Jackson Providers and
one/1000 nested object elements, root `wrap(Map)` allocates 104 B/op (11.6–15.3 ns/op)
and `wrap(List)` allocates 96 B/op (12.5–12.6 ns/op), independent of source size.
The fixed JDK delegates protect collection views, iterators and nested access.
Copying a Map root allocates 456 B/op for one element and about 260337 B/op for
1000; List roots allocate 272 and about 260081 B/op. The 1000-element copies take
about 23.0–26.1 µs/op. Borrowed/copied fused access both allocate 88 B/op in this
fixture. These are ranges of measured means, not confidence bounds or universal
limits. Root wrapping is constant-cost; node access, binding, export and explicit
deep copies still have costs.
At depth four in the mixed object/array fixture, chained node access measures
32.4–33.7 ns/op and 248 B/op; a precompiled Pointer measures about 15.8 ns/op
and 88 B/op. This comparison returns the selected node without scalar conversion.

Collection JMH uses the same Java 21 settings with three Providers and one/1000
object rows. Ordinary JDK/neutral get, nested get and replacement put measure
about 1–4 ns/op without per-operation allocation; compatibility scalar get and
replacement put also allocate nothing. Reading a nested array then its object
through compatibility getters costs 128 B/op and about 13.6–14.1 ns/op. Repeated
legacy views are deliberately not cached. `node(...).asInt()` costs about 32–42
ns/op and 336–592 B/op here because it preserves node diagnostics and configured
Provider conversion; ordinary get has a different contract.

The roundtrip baseline below is the same configured facade mapper using ordinary
JDK Object binding, not a direct native-engine baseline. Input construction is
outside measurement; each operation parses and writes UTF-8 bytes for 1000 rows.
Time is mean ± JMH 99.9% error:

| Provider | JDK containers µs/op | Deprecated containers µs/op | JDK / deprecated B/op |
| --- | ---: | ---: | ---: |
| Jackson 2 | 113.7 ± 1.4 | 115.2 ± 7.2 | 483519 / 483731 |
| Jackson 3 | 113.1 ± 3.9 | 132.9 ± 21.4 | 451347 / 451516 |
| fastjson2 | 124.9 ± 77.2 | 140.2 ± 77.5 | 360331 / 360379 |

Transparent storage delegation removes about 264 kB/op of serialization wrappers
from the earlier compatibility implementation in this fixture; the remaining
allocation difference is about 48–212 B/op. The latest common-API compatibility
revision remeasures 15 cases (three engines × get/put/nested/ordinary and legacy
roundtrips) at 1000 rows. The shared JSON base stores one mapper reference and
root binding reuses immutable JsonType constants; no per-container helper cache is
added. Timing intervals overlap, especially
for fastjson2, so these results do not establish zero overhead or a universal speedup.
Neutral `readTree` roundtrips allocate about 252 kB/op more than JDK binding for
1000 rows because tree creation explicitly copies native binding results; use
ordinary DTO/Map/List binding when an independent editable tree is unnecessary.

The synchronous array benchmark uses the same mapper configuration, input,
binding type, count/checksum and array/EOF checks for native, cursor and iterator
paths. Java 21, a 512 MiB heap, GC profiling, two forks, five 500 ms warmups
and five 500 ms measurements cover 65536 records with 1000-character text.
Map and DTO documents are 64.6875 MiB; nested generic rows add one list per element.
Time is mean ± JMH 99.9% error; allocation includes native binding:

| Provider / element | Before iterator ms/op | Current iterator ms/op | Before / current B/record |
| --- | --- | --- | --- |
| jackson2 / map | 24.62 ± 0.78 | 22.78 ± 1.17 | 1528.02 / 1376.02 |
| jackson2 / dto | 23.41 ± 1.00 | 23.44 ± 1.35 | 1200.02 / 1200.02 |
| jackson2 / nested | 32.83 ± 5.84 | 29.62 ± 9.11 | 1760.02 / 1456.03 |
| jackson3 / map | 30.43 ± 8.66 | 23.30 ± 1.43 | 1544.02 / 1392.03 |
| jackson3 / dto | 26.82 ± 1.68 | 26.20 ± 1.27 | 1424.02 / 1440.02 |
| jackson3 / nested | 28.54 ± 0.98 | 24.14 ± 0.99 | 1776.02 / 1472.03 |

Skipping repeated generic Type resolution removes about 152 B/Map record and
304 B/nested-list record on both Providers. Those two current paths match the
configured native loop's per-record allocation in this workload; that does not
establish zero overhead for other types or call shapes. DTO allocation shows
JIT-dependent variation without a stable improvement. Timing intervals overlap
for several comparisons, so the allocation result is not a universal CPU claim.

An isolated same-type preparation benchmark allocates 152 B for Map and 304 B
for nested generic resolution on both native engines, despite TypeFactory cache
hits; class-only resolution is effectively allocation-free. Retaining only the
current typed reader without the JDK Type marker did not reduce these costs.
Each cursor retains one Type/reader pair, not a map or shared context; native
binding contexts and object IDs remain per value.

The event benchmark separates configured native async parsing, direct Session
receiver delivery, Reactor delivery, synchronous cursor output and complete event
transcoding. Source construction is outside measurement. Write references
publish owned arrays at the same per-event flush boundaries; the cursor lacks
Reactor's demand/cancellation/budget machinery and remains a lower-bound reference.
Java 21, 512 MiB heap, two forks, three 500 ms warmups and four 500 ms
measurements cover 1000 records (8002 events). Total allocation is:

| Provider / text chars | Native parse B/op | Session B/op | Reactor read B/op | Cursor write B/op | Reactor write B/op |
| --- | --- | --- | --- | --- | --- |
| jackson2 / 20 | 113153 | 234362 | 235471 | 216443 | 1002064 |
| jackson2 / 1000 | 1095877 | 1223437 | 1230320 | 1200461 | 1986092 |
| jackson3 / 20 | 113249 | 234442 | 235568 | 216539 | 1002167 |
| jackson3 / 1000 | 1095978 | 1223523 | 1230413 | 1200561 | 1986205 |

Session event materialization adds about 120 kB/op over the native parser here;
five text-bearing tokens per record produce immutable event objects. Reactor
reading adds about 1.1–6.9 kB/op over Session delivery as input chunk count rises.
Reactor writing adds about 786 kB/op over cursor output, or about 98 B/event,
including inner subscriptions and output ownership. These differences include
required contracts, not wholly removable waste. Current implementation retains
zero prefetch, per-event delivery and cancellation-safe ownership; no custom
Publisher, pooled public events or weakened validation is introduced.
For 20-character records, native/Session/Reactor reads take 86/149/193 µs on
Jackson 2 and 83/140/195 µs on Jackson 3. Cursor/Reactor writes take 185/438 µs
and 187/411 µs respectively. The short-event path has material CPU overhead;
longer-text timing also varies by fork, so matching allocation does not establish
negligible CPU cost.

Large-string references support textKind=ascii/unicode/escaped and an explicit
recordCount, comparing native ObjectWriter, native generator and facade output
with identical owned-result consumption. Different text encodings and sinks can
change native/JIT behavior; compare each workload's forks rather than interpreting
one fastest result as a universal facade overhead estimate.
Jackson 3 repeats use eight values, 1048576 UTF-16 code units of text per value
(Unicode is rounded down to complete the repeating unit), two forks, four 500 ms
warmups and four 500 ms measurements:

| JDK / text | Native writer ms/op | Native generator ms/op | Facade ms/op | Facade allocation increase over writer |
| --- | --- | --- | --- | --- |
| 17 / ASCII | 3.66 ± 0.38 | 3.65 ± 0.18 | 3.90 ± 0.52 | 0.35% |
| 17 / Unicode | 7.37 ± 0.37 | 8.08 ± 2.48 | 8.08 ± 0.52 | 1.11% |
| 17 / escaped | 4.10 ± 0.45 | 4.03 ± 0.15 | 4.53 ± 0.33 | 0.65% |
| 21 / ASCII | 5.99 ± 0.82 | 6.64 ± 2.55 | 6.26 ± 1.07 | 0.35% |
| 21 / Unicode | 7.98 ± 1.05 | 8.24 ± 1.34 | 8.20 ± 0.47 | 1.10% |
| 21 / escaped | 7.08 ± 1.02 | 7.35 ± 0.30 | 8.55 ± 1.45 | 0.65% |

Java 21 ASCII neighbors at 524288/2097152 code units take native writer
5.86 ± 1.40 / 11.33 ± 0.62 ms and facade 5.91 ± 0.67 / 11.77 ± 0.66 ms.
These repeats do not stably reproduce the earlier multi-fold timing split.
Native paths also vary with JDK/workload, and the precise compilation/runtime
cause remains unresolved. No size threshold, JVM tuning or replacement encoder
is introduced. UTF-16 text length is not output byte length; Unicode and escaped
values produce larger documents and must be compared within the same workload.

After `mvn -B -ntp verify`, run optional checks from the repository root:

```sh
python3 scripts/run-tree-access-stress.py
python3 scripts/run-fastjson2-stress.py
python3 scripts/run-streaming-stress.py
python3 scripts/run-resource-release-stress.py
python3 scripts/run-netty-stress.py
python3 scripts/run-netty-stress.py --release-only
python3 scripts/run-databuffer-stress.py
python3 scripts/run-databuffer-stress.py --release-only
python3 scripts/run-streaming-benchmarks.py
python3 scripts/run-streaming-benchmarks.py --tree
python3 scripts/run-streaming-benchmarks.py --tree-wrap
python3 scripts/run-streaming-benchmarks.py --global-lookup
python3 scripts/run-streaming-benchmarks.py --iterator
python3 scripts/run-streaming-benchmarks.py --iterator -p payload=map,dto,nested -p records=65536 -p textSize=1000
python3 scripts/run-streaming-benchmarks.py --events -p textSize=20,1000
python3 scripts/run-streaming-benchmarks.py --provider-read
python3 scripts/run-streaming-benchmarks.py --published-write
python3 scripts/run-streaming-benchmarks.py --published-write -p textSize=1048576 -p recordCount=8 -p textKind=ascii,unicode,escaped
python3 scripts/run-streaming-benchmarks.py --operation-options
python3 scripts/run-streaming-benchmarks.py --fastjson2
python3 scripts/run-streaming-benchmarks.py --netty
```

Stress drivers generate and validate data without collecting a whole document.
`JSON_STRESS_JAVA` selects their JVM. Benchmark options pass through to JMH,
including forks, warmup, measurement, heap and GC profiling. Compare allocation,
live heap, pinned/reserved memory and RSS separately. See the
[PR](https://github.com/jetlinks/json-facade/pull/1) for delivery evidence.
