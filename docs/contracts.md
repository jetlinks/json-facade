# Public contracts

## Boundary and configuration

The facade handles explicit JSON calls in programs: binding, conversion, tree manipulation, cursor operations and manually composed byte streams. HTTP request/response codecs, WebClient, SSE and ORM integrations use their official configuration. The library has no Spring/Netty dependency or auto-configuration. Pooled buffers are converted and released by their existing host owner.

`JsonProvider` is the only engine factory SPI. `JsonModule`/`JsonAdapter` express neutral binding extensions; native callbacks stay in engine-specific modules. The immutable configuration is fully applied before publishing a mapper. Ordinary callers never receive the native mapper, and no operation mutates a global registry.

Explicit Provider selection wins. Discovery is scoped to the builder's ClassLoader, with no permanent ClassLoader cache. An ambiguous classpath, duplicate Provider/module identity, duplicate exact-class adapter or mismatched native extension fails before use. Unsupported configuration is never ignored.

Options define lossless untyped decimal binding, unknown properties, duplicate keys, omission of nulls, pretty synchronous output, parser depth/string/number constraints and a materialized-record byte budget. Streaming output is always compact, including when synchronous output is pretty. Named scenario instances are a host concern.

## Types, tree and cursors

`JsonType<T>` stores JDK Type and captures nested generics without Jackson or Spring types. Conversion is JSON binding, not bean copying; object identity is not preserved. `read` requires one full root value and rejects trailing nonwhitespace content. Empty input differs from JSON null.

`JsonValue` is the common tree interface; `JsonObject` and `JsonArray` expose typed mutable containers. Provider-created mappers own `createObject()` / `createArray()` and Map/Collection initialization. The default implementation uses JDK containers. Initializers and put/add/set copy nested JSON containers; explicit nulls are retained and all object keys must be Strings. Inputs must be acyclic JSON-compatible values; use configured `toTree` for arbitrary POJOs. `JsonValue.of` copies Java containers but retains an existing node's identity. Its static object/array methods are configuration-free structural shortcuts.

`NULL` and `MISSING` are distinct non-null sentinel nodes. `get`/`at` return MISSING for absent locations; RFC 6901 pointers support escaped `/` and `~`. Array indices use canonical decimal notation. `asObject`/`asArray` reject other shapes. `toJavaValue` exports an independent Map/List snapshot; `deepCopy` returns an independent tree. Cross-Provider nodes obey the same logical-value contract. Provider binding can synchronously borrow its own JDK tree without making an export snapshot, but cannot expose or retain that backing graph. Trees are not thread-safe.

Reader `readValue`/`skipValue` consume the whole current value. Its next `next()` returns the successor token. Readers/writers/sessions are operation-local and not thread-safe. Their close methods release internal state and do not close caller sources/targets. Writer close does not repair incomplete containers. UTF-8 is the byte encoding; character sources/targets keep their character semantics.

## Incremental reads

| Mode | Contract |
| --- | --- |
| SINGLE_VALUE | One complete root value, including a root array bound as one collection |
| ARRAY_ELEMENTS | Root must be an array; direct elements are emitted, nested arrays stay whole |
| NDJSON | LF/CRLF lines each contain one root value; blank lines skip; final LF optional; multiline records and multiple values per line fail |
| ROOT_VALUES | Complete root values separated by JSON whitespace; no automatic array flattening |

SINGLE_VALUE and ARRAY_ELEMENTS reject empty input. NDJSON and ROOT_VALUES permit zero records. Scalars such as numbers are not complete merely because an input chunk ended; the engine must see a separator or true EOF.

Selection is an RFC 6901 pointer on a SINGLE_VALUE document. It selects either the pointed value or direct elements of the pointed array. Missing path and wrong target shape fail; an empty target array completes normally. Duplicate occurrences of the selected path fail, since previously emitted values cannot be retracted to implement LAST_WINS. Other duplicate-field policies follow the configured engine. Unrelated subtrees are tokenized and constrained, but never collected into a whole-document tree.

One Session per operation/subscription. Feed only when needsInput is true. Feed borrows the remaining ByteBuffer region without changing its cursor; callers keep bytes alive and stable until the session requests input again or closes. Read returns exactly one VALUE (possibly Java null), NEED_INPUT or END. endInput signals actual source EOF, and the caller then reads until END. Incomplete UTF-8, escape, number or container input fails. Close is idempotent.

The current selected record is buffered as engine tokens and bound by the configured engine reader, without converting through a JDK tree or applying serialization policies during a read. Explicit nulls, duplicate-field tokens, numeric policies and custom readers retain their input semantics. Numeric text requested by an adapter or String binding retains the native tokenizer's representation, including decimal scale and exponent notation. This is not a source-text preservation API: both native nonblocking tokenizers normalize integer `-0` to `0`, unlike their synchronous counterparts. Paths are tracked only for pointer selection, using matched-prefix state on the container stack rather than complete per-token path copies. Byte limits and parser token constraints bound that record and the parser's token buffers; total stream length and unrelated document size are caller budgets. Cancelling after a selected prefix does not validate the remainder of its document. Consumers requiring full validation must consume through END.

## Reactor and memory ownership

`JsonStreamMapper.of(mapper)` shares the configured mapper. Both synchronous and reactive operations use the same neutral/native adapters. Returned publishers are lazy, with independent per-subscription state; repeatability still depends on the source publisher.

readBytes accepts independently owned, immutable-after-publication arrays. readBuffers copies the source's remaining region immediately at the adapter's input onNext before queuing; the original position, limit and mark are preserved, and source storage may be reused after that call. Copying only a read-only view would not protect shared storage. No retain/release protocol is inferred from a ByteBuffer. Asynchronous boundaries before the library still require the upstream owner to keep data valid until delivery.

The adapter serializes one input chunk at a time using Reactor's demand-driven operators. No unbounded result queue, internal subscribe, collectList/join, blocking source bridge or forced Scheduler. Cancellation cancels the input without waiting for an active parser operation; the operation owner then closes parser state exactly once before releasing ownership. Upstream errors retain identity. Reactor Context follows the operator chain. Cancellation is observed between parser tokens and skipped null records; an already running custom binding callback must return before its parser state can be released.

writeBytes/writeBuffers use ARRAY, LF-delimited NDJSON/ROOT_VALUES, or exactly one bounded SINGLE_VALUE. Empty ARRAY emits brackets; empty NDJSON/ROOT_VALUES emits nothing; empty or multiple SINGLE_VALUE records fail. A compact native generator is reused per subscription, with a fresh binding context for each complete value so object-ID tracking cannot retain earlier records. Bulk writes fill bounded owned segments; full segments transfer directly and only a partial tail may be copied. JSON record bytes are budgeted separately from transport delimiters. Compact output is selected for that operation without modifying the shared mapper; no JSON whitespace filter is applied after serialization. A chunk need not match a record. Demand bounds emitted records/chunks, not token work or heap bytes; the adapter may prepare one next record when a previous inner publisher completes. Output buffers are read-only with independent cursors and memory never reused by the library. A serialization error stops output without adding a normal array terminator; previously emitted bytes cannot be rolled back.

`readEvents` / `readBufferEvents` emit immutable `JsonEvent` tokens of exactly one complete document, including all container boundaries, field names and scalars. They never buffer an enclosing container. Token text/depth and byte limits remain effective; incomplete tokens still require parser storage. Numeric events carry tokenizer text rather than passing through double; the native negative-zero limitation above still applies. Structural constants may be shared; public payload events cannot be mutated or recycled.

`writeEvents` / `writeEventBuffers` require SINGLE_VALUE output and validate one balanced document, including object field/value ordering. Token events can build arbitrary nested structures, while `JsonEvent.value(javaValue)` binds one complete byte-bounded value using the configured mapper. Each event is drained before subsequent events advance, so a huge aggregate can be constructed without assembling its complete tree. The current token/bound value remains a synchronous write unit. Container events carry no payload; string/number token text obeys mapper limits. Scalar tokens use the direct `JsonWriter.string/bool/nullValue/number` operations and bypass Java binding adapters. Bound values use the normal binding rules and byte budget. Grammar-validated numeric text cannot inject raw JSON. Errors and cancellation leave an incomplete document, with no automatic repair.

Total document size, current input chunk, token size, depth, current bound value and pending output segments are separate budgets. A 1 GiB array of bounded records or event-based document can run in a much smaller heap. Whole-value binding of a 1 GiB POJO would still materialize that value; callers should stream its structure instead. Individual enormous strings/numbers remain constrained tokens. The library does not suspend or replay an arbitrary native serializer at a byte boundary.

The library owns no background threads, global live queues or long-lived sessions, so it adds no MBean or per-record tracing. The host retains its observation context and business-level tracing.

## Implementation and validation

Framing and selection live in `json-facade-api/src/main/java/org/jetlinks/json/internal/AbstractReadSession.java`; each Provider's `Jackson2Session` / `Jackson3Session` owns native token buffering and binding. Internal token metadata is session-local and reused; public events remain immutable and independently retainable. Native session/writer hot paths call the engine directly and share exception classification through each Provider's support class. `JsonMapper.compactWriter` selects operation-local output configuration and suppresses implicit root separators. Provider tree serializers live separately from exact-class user adapters, so tree-interface inheritance does not widen user adapter matching. `OperationResource` owns serial operation/cancellation cleanup; `ReadSessionResource` and `WriteSessionResource` release native state exactly once. Each resource reuses a drain publisher across sequential chunks/records/events; every inner subscription has independent demand and completion state. `concatMap` with zero prefetch preserves serial advancement, and deferred start/EOF/finish operations preserve subscription timing. `SegmentedJsonOutput` owns byte budgets and segment transfer; `DocumentState` validates structural output.

`mvn -o -B -ntp verify` runs warning-as-error compilation, Checkstyle, SpotBugs, both real-engine TCKs, Reactor tests and the aggregate coverage gate. Production modules each require at least 80% branch coverage. CI uses Java 17/21 and the smaller bounded-heap stress matrix.

Reproducible opt-in measurements (after the build):

```sh
# Full 1 GiB logical documents, both engines, 128/256 MiB heaps.
python3 scripts/run-streaming-stress.py
# Chunk-boundary/slow-demand coverage with smaller logical documents.
python3 scripts/run-streaming-stress.py --mib 16 --heaps 128 --chunks 64 65536
# Optional allocation/GC recording, kept outside ordinary CI.
python3 scripts/run-streaming-stress.py --heaps 128 --modes array-write document-copy --jfr-dir target/streaming-jfr
# JMH throughput and cumulative allocations; optional JMH dependencies resolved by Maven.
python3 scripts/run-streaming-benchmarks.py
```

The stress harness lazily generates source bytes, requests one record/chunk at a time and validates input records without collecting them. Output is independently checked with a native nonblocking parser, including trailing metadata. It reports bytes, records, elapsed time, first-signal latency, cumulative thread allocation, GC time, current heap and the sum of heap-pool peaks. Pool peaks need not occur simultaneously and are not a precise process RSS measurement. JMH uses separate forks and GC profiling; its microbenchmarks do not imply production/network throughput.

Validation covers both engines in all six stress modes with at least 1 GiB per document and 128/256 MiB maximum heaps, plus 16 MiB documents split into 64 B/64 KiB chunks. Java 17 and 21 each pass the full 94-test quality gate. JFR recordings of 1 GiB array output and structured document copying under a 128 MiB maximum heap show approximately 3.8–6.6 MiB live after GC. The linked PR records the measured allocation comparison and delivery evidence.

Review and CI entry: [jetlinks/json-facade#1](https://github.com/jetlinks/json-facade/pull/1).

## Remaining boundaries

Published public native JSON types, Jackson annotations/mixins and custom engine modules need explicit migration. A facade cannot transparently make these engine-neutral. CBOR, Smile and XML are separate formats and remain with their native integrations. There is no automatic engine fallback, implicit broken-record skipping or replacement of framework JSON configuration.
