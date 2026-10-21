# Public contracts

For installation and examples, see the [Chinese user guide](user-guide.md).
This document describes the current implementation and its verification boundaries.

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
fail before use. Parse failures never cause engine fallback. A Provider must
support the public incremental contracts; collecting a stream is not a substitute.

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

## Trees, locations and validation

`JsonValue`, `JsonObject` and `JsonArray` are neutral interfaces; current mapper
factories use JDK storage. Structural factories accept acyclic JSON-compatible
scalars, String-keyed Maps, Collections and Java arrays. Arbitrary POJOs use
configured `toTree`. Structural byte[] becomes an array; native binding can
encode it as Base64.

Initializers, insertion, `deepCopy` and `toJavaValue` defensively copy nested
containers. `JsonValue.of(existingNode)` retains that node's identity. Structural
numbers accept exactly Byte, Short, Integer, Long, Float, Double, BigInteger and
BigDecimal; other Number classes, including subclasses of the latter two, fail
before mutation. Use `toTree` for configured binding of other Java numbers.
Binding results still require defensive copying: custom native deserializers
may return shared containers. Synchronous binding may borrow the internal JDK
tree but must not expose or retain its backing graph.

Missing and JSON null are distinct non-null views; inspect `isMissing/isNull`,
not singleton identity. Missing export fails. RFC 6901 pointers support escaped
`/` and `~`; readable array indices use canonical decimal notation.
`getObject/getArray` accept names or indices and keep missing/null locations
chainable without creating data. Actual shape mismatches fail; absent views
cannot be mutated. `asObject/asArray` require an actual matching container.

Scalar defaults apply only to missing/null input: primitive methods use zero or
false, reference methods use null, and explicit defaults are available. Present
values use configured Provider conversion and propagate failures. Static
structural factories have no mapper; present-value conversion requires an
explicit mapper or a mapper-created/bound tree. There is no global fallback.
Nested views and deep copies preserve mapper configuration; copies start at the
root, while inserted data uses its destination configuration and location.

Access paths belong to views, not backing data. They retain immutable path
segments rather than ancestor nodes; strings, pointers and failures are rendered
on demand. Foreign absent views snapshot public pointer/display locations without
retaining the source node. A compiled Pointer traverses the JDK tree in O(d),
creating only the final view; mixed array paths may need O(d) index metadata.
Cached views continue to refer to their original container after ancestor replacement.

`requirePresent`, `requireNonNull` and predicate `require` return the current
view on success. Lazy exception factories receive immutable `JsonFailure`;
their exception instances and predicate failures propagate unchanged. Default
assertion failures have VALIDATION kind. Failures expose kind, reason, message,
RFC 6901 pointer, display path and original cause. Provider paths are extracted
from structured native errors and prefixed with the access path.

Trees are mutable and not thread-safe. There is no public field iterator,
array remove/insert/clear API or structural node equality contract. Exporting a
Map/List snapshot copies data; large traversal should use cursors or events.

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
cannot be replaced. Recursive initialization fails. Failed unreserved default
discovery does not publish a partial instance.

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
Context close never resets globals. Bootstrap drops BeanFactory references after
publication or failed startup, but the reservation remains. Native handlers may
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
uses zero prefetch. ARRAY emits `[]` for an empty source; NDJSON/ROOT_VALUES emit
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

Java 17.0.18 and 21.0.10 each pass 233 tests with no failures, errors or skips.
Compilation uses all warnings as errors; Checkstyle, SpotBugs and every production
module's 80% branch-coverage gate pass. Tests cover both real Providers, configured
extensions, tree isolation/locations, IO failures, demand, cancellation and transport
ownership. The [CI matrix](../.github/workflows/verify.yml) also runs small-chunk,
closed-owner, tree-retention and pooled-buffer regressions.

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

After `mvn -B -ntp verify`, run optional checks from the repository root:

```sh
python3 scripts/run-tree-access-stress.py
python3 scripts/run-streaming-stress.py
python3 scripts/run-resource-release-stress.py
python3 scripts/run-netty-stress.py
python3 scripts/run-netty-stress.py --release-only
python3 scripts/run-databuffer-stress.py
python3 scripts/run-databuffer-stress.py --release-only
python3 scripts/run-streaming-benchmarks.py
python3 scripts/run-streaming-benchmarks.py --tree
python3 scripts/run-streaming-benchmarks.py --provider-read
python3 scripts/run-streaming-benchmarks.py --published-write
python3 scripts/run-streaming-benchmarks.py --netty
```

Stress drivers generate and validate data without collecting a whole document.
`JSON_STRESS_JAVA` selects their JVM. Benchmark options pass through to JMH,
including forks, warmup, measurement, heap and GC profiling. Compare allocation,
live heap, pinned/reserved memory and RSS separately. See the
[PR](https://github.com/jetlinks/json-facade/pull/1) for delivery evidence.
