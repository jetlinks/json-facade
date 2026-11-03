package org.jetlinks.json.reactor;

import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonWriteOptions;
import org.jetlinks.json.JsonWriter;
import org.jetlinks.json.reactor.spi.JsonBufferAllocator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

/** One compact generator/context per subscription. Each synchronous write unit is byte bounded. */
final class WriteSessionResource<B> extends OperationResource {
    private final JsonWriteOptions.Format format;
    private final SegmentedJsonOutput<B> output;
    private JsonWriter writer;
    private DocumentState document;
    // Only one concatMap inner subscribes at a time; queued bytes stay in the owned output.
    // Disable generator fusion so cancellation cannot strand a polled transport reference.
    private final Flux<OutputSegment<B>> drain = Flux.<OutputSegment<B>>generate(this::emit).hide();
    private boolean recordWritten;

    WriteSessionResource(JsonMapper mapper, JsonWriteOptions options, boolean events, JsonBufferAllocator<B> allocator) {
        format = options.format();
        output = new SegmentedJsonOutput<>(options.chunkSize(),
                Math.min(options.maxValueBytes(), mapper.options().maxValueBytes()), this::cancelled, allocator);
        writer = mapper.compactWriter(output);
        document = events ? new DocumentState(mapper.options()) : null;
    }

    Flux<OutputSegment<B>> start() {
        if (acquire()) {
            try {
                output.beginUnit(0);
                if (format == JsonWriteOptions.Format.ARRAY) {
                    writer.beginArray();
                }
                flush();
            } finally {
                release();
            }
        }
        return drain;
    }

    Flux<OutputSegment<B>> encode(Object value) {
        if (acquire()) {
            try {
                if (format == JsonWriteOptions.Format.SINGLE_VALUE && recordWritten) {
                    throw new JsonException(JsonException.Kind.SYNTAX, "Trailing root value");
                }
                int framing = format == JsonWriteOptions.Format.ARRAY ? (recordWritten ? 1 : 0) : 0;
                boolean delimited = format == JsonWriteOptions.Format.NDJSON
                        || format == JsonWriteOptions.Format.ROOT_VALUES;
                output.beginUnit(framing, delimited ? 1 : 0);
                writer.value(value);
                writer.flush();
                if (delimited) {
                    // Budget the JSON value separately from its transport delimiter.
                    output.beginUnit(1);
                    output.write('\n');
                }
                output.seal();
                recordWritten = true;
            } finally {
                release();
            }
        }
        return drain;
    }

    Flux<OutputSegment<B>> event(JsonEvent event) {
        if (acquire()) {
            try {
                output.beginUnit(document.prefixBytes(event));
                document.write(event, writer);
                flush();
            } finally {
                release();
            }
        }
        return drain;
    }

    Flux<OutputSegment<B>> finish() {
        if (acquire()) {
            try {
                output.beginUnit(0);
                if (document != null) {
                    document.finish();
                } else if (format == JsonWriteOptions.Format.ARRAY) {
                    writer.endArray();
                } else if (format == JsonWriteOptions.Format.SINGLE_VALUE && !recordWritten) {
                    throw new JsonException(JsonException.Kind.SYNTAX, "Empty JSON document");
                }
                flush();
            } finally {
                release();
            }
        }
        return drain;
    }

    private void emit(SynchronousSink<OutputSegment<B>> sink) {
        if (!acquire()) {
            sink.complete();
            return;
        }
        OutputSegment<B> bytes;
        try {
            bytes = output.poll();
        } finally {
            release();
        }
        // Release ownership before a completion signal can synchronously advance concatMap.
        if (bytes == null) {
            sink.complete();
        } else {
            sink.next(bytes);
        }
    }

    private void flush() {
        writer.flush();
        output.seal();
    }

    @Override
    protected void closeOwned() {
        // Closing a failed/cancelled generator may flush bytes, but must not publish or retain them.
        JsonWriter owned = writer;
        writer = null;
        document = null;
        try {
            output.discard();
        } finally {
            owned.close();
        }
    }
}
