# Public contracts

## Boundary and configuration

The facade handles explicit JSON calls in programs: binding, conversion, tree manipulation, cursor operations and manually composed byte streams. HTTP request/response codecs, WebClient, SSE and ORM integrations use their official configuration. The library has no Spring/Netty dependency or auto-configuration. Pooled buffers are converted and released by their existing host owner.

`JsonProvider` is the only engine factory SPI. `JsonModule`/`JsonAdapter` express neutral binding extensions; native callbacks stay in engine-specific modules. The immutable configuration is fully applied before publishing a mapper. Ordinary callers never receive the native mapper, and no operation mutates a global registry.

Explicit Provider selection wins. Discovery is scoped to the builder's ClassLoader, with no permanent ClassLoader cache. An ambiguous classpath, duplicate Provider/module identity, duplicate exact-class adapter or mismatched native extension fails before use. Unsupported configuration is never ignored.

Options define lossless untyped decimal binding, unknown properties, duplicate keys, omission of nulls, pretty synchronous output, parser depth/string/number constraints and a materialized-record byte budget. Streaming output is always compact, including when synchronous output is pretty. Named scenario instances are a host concern.

## Types, tree and cursors

`JsonType<T>` stores JDK Type and captures nested generics without Jackson or Spring types. Conversion is JSON binding, not bean copying; object identity is not preserved. `read` requires one full root value and rejects trailing nonwhitespace content. Empty input differs from JSON null.

`JsonValue` is a mutable JDK-backed tree. `NULL` and `MISSING` are distinct non-null sentinel nodes. `get`/`at` return MISSING for absent locations; RFC 6901 pointers support escaped `/` and `~`. Object field names are Strings and array indices use canonical decimal notation. No JSONPath expression language is provided. `toJavaValue` exposes the tree's backing JDK value intentionally; use `deepCopy` when independent mutation is required. Trees are not thread-safe.

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

The current selected record is materialized and bound through the same configured mapper. Byte limits and parser token constraints bound that record and the parser's token buffers; total stream length and unrelated document size are caller budgets. Cancelling after a selected prefix does not validate the remainder of its document. Consumers requiring full validation must consume through END.

## Reactor and memory ownership

`JsonStreamMapper.of(mapper)` shares the configured mapper. Both synchronous and reactive operations use the same neutral/native adapters. Returned publishers are lazy, with independent per-subscription state; repeatability still depends on the source publisher.

readBytes accepts independently owned, immutable-after-publication arrays. readBuffers copies the source's remaining region immediately at the adapter's input onNext before queuing; the original position, limit and mark are preserved, and source storage may be reused after that call. Copying only a read-only view would not protect shared storage. No retain/release protocol is inferred from a ByteBuffer. Asynchronous boundaries before the library still require the upstream owner to keep data valid until delivery.

The adapter serializes one input chunk at a time using Reactor's demand-driven operators. No unbounded result queue, internal subscribe, collectList/join, blocking source bridge or forced Scheduler. Cancellation cancels the input and closes parser state; upstream errors retain identity. Reactor Context follows the operator chain. Parser cleanup is serialized with bounded synchronous parsing.

writeBytes/writeBuffers use ARRAY or LF-delimited NDJSON/ROOT_VALUES. Empty ARRAY emits brackets; the others emit nothing. One bounded record is encoded at a time and split into demand-driven chunks. A chunk need not match a record. Output buffers are read-only with independent cursors and memory never reused by the library. A serialization error stops output without adding a normal array terminator; previously emitted bytes cannot be rolled back.

The library owns no background threads, global live queues or long-lived sessions, so it adds no MBean or per-record tracing. The host retains its observation context and business-level tracing.

## Remaining boundaries

Published public native JSON types, Jackson annotations/mixins and custom engine modules need explicit migration. A facade cannot transparently make these engine-neutral. CBOR, Smile and XML are separate formats and remain with their native integrations. There is no automatic engine fallback, implicit broken-record skipping or replacement of framework JSON configuration.
