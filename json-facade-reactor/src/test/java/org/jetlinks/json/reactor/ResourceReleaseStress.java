package org.jetlinks.json.reactor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonType;

/** Bounded-heap regression: retain closed owners while their former buffers exceed the heap.
 * Each process has 64 MiB heap / 32 MiB direct memory; no explicit GC or private-field inspection.
 * Run with scripts/run-resource-release-stress.py after test-compile.
 */
public final class ResourceReleaseStress {
    private static final int BYTES = 4 * 1024 * 1024;
    private static final int OWNERS = 64;

    private ResourceReleaseStress() {
    }

    public static void main(String[] args) {
        String engine = args[0];
        String mode = args[1];
        JsonMapper mapper = StreamingStress.mapper(engine);
        List<Object> closed = new ArrayList<>();
        for (int i = 0; i < OWNERS; i++) {
            closed.add(switch (mode) {
                case "session", "direct", "session-error" -> session(mapper, mode);
                case "reader", "reader-error" -> reader(mapper, mode.endsWith("error"));
                case "writer", "writer-error" -> writer(mapper, mode.endsWith("error"));
                case "queue" -> queue();
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            });
        }
        // Keep every closed facade reachable until after all allocations, defeating dead-local elimination.
        Reference.reachabilityFence(closed);
        System.out.printf("{\"engine\":\"%s\",\"mode\":\"%s\",\"closedOwners\":%d,\"maxHeap\":%d}%n",
                engine, mode, closed.size(), Runtime.getRuntime().maxMemory());
    }

    private static Object session(JsonMapper mapper, String mode) {
        ByteBuffer input = mode.equals("direct") ? ByteBuffer.allocateDirect(BYTES) : ByteBuffer.allocate(BYTES);
        boolean invalid = mode.equals("session-error");
        input.put((byte) (invalid ? '[' : '1')).put((byte) (invalid ? '}' : ' ')).flip();
        var session = mapper.readSession(JsonType.of(Integer.class),
                JsonReadOptions.of(JsonReadOptions.Mode.ROOT_VALUES));
        try (session) {
            session.feed(input);
            if (invalid) {
                fails(session::read, JsonException.Kind.SYNTAX);
            } else {
                require(Integer.valueOf(1).equals(session.read().value()), "record");
            }
        }
        session.close();
        require(!session.needsInput(), "Closed session requested input");
        return session;
    }

    private static Object reader(JsonMapper mapper, boolean invalid) {
        byte[] input = new byte[BYTES];
        input[0] = (byte) (invalid ? '[' : '1');
        input[1] = (byte) (invalid ? '}' : ' ');
        var reader = mapper.reader(new ByteArrayInputStream(input, 0, 2));
        try (reader) {
            reader.next();
            if (invalid) {
                fails(reader::next, JsonException.Kind.SYNTAX);
            }
        }
        reader.close();
        return reader;
    }

    private static Object writer(JsonMapper mapper, boolean invalid) {
        OutputStream target = invalid ? new FailingOutput() : new ByteArrayOutputStream(BYTES);
        var writer = mapper.writer(target);
        writer.string("value");
        if (invalid) {
            fails(writer::close, JsonException.Kind.IO);
            require(!((FailingOutput) target).closed, "Closed caller target");
        } else {
            writer.close();
        }
        writer.close();
        return writer;
    }

    private static Object queue() {
        var output = new SegmentedJsonOutput<>(1, 256 * 1024, () -> false, ByteArrayAllocator.INSTANCE);
        output.beginUnit(0);
        output.write(new byte[256 * 1024], 0, 256 * 1024);
        output.discard();
        output.discard();
        require(output.poll() == null, "Retained output segment");
        return output;
    }

    private static void fails(Runnable operation, JsonException.Kind kind) {
        try {
            operation.run();
        } catch (JsonException error) {
            require(error.kind() == kind, "Wrong error kind: " + error.kind());
            return;
        }
        throw new AssertionError("Expected " + kind);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FailingOutput extends OutputStream {
        private final byte[] payload = new byte[BYTES];
        private boolean closed;

        @Override
        public void write(int value) throws IOException {
            throw new IOException("Expected target failure " + payload.length);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
