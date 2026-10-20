package org.jetlinks.json.reactor;

import java.nio.charset.StandardCharsets;
import org.jetlinks.json.JsonException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedJsonOutputTest {
    @Test
    void budgetIsCheckedBeforeAppendingAndFullSegmentsKeepTheirStorage() {
        var output = new SegmentedJsonOutput<>(2, 5, () -> false, ByteArrayAllocator.INSTANCE);
        output.beginUnit(0);
        byte[] value = "\"中\"".getBytes(StandardCharsets.UTF_8);
        output.write(value, 0, value.length);
        output.write(value, 0, 0);
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> output.write('!')).kind());
        output.seal();
        assertArrayEquals(new byte[]{value[0], value[1]}, output.poll().take());
        assertArrayEquals(new byte[]{value[2], value[3]}, output.poll().take());
        assertArrayEquals(new byte[]{value[4]}, output.poll().take());
        assertNull(output.poll());
        output.beginUnit(0);
        output.write('[');
        assertThrows(JsonException.class, () -> output.write(value, 0, value.length));
        output.seal();
        byte[] retained = output.poll().take();
        output.beginUnit(0);
        output.write(']');
        output.seal();
        assertArrayEquals(new byte[]{'['}, retained);
        assertArrayEquals(new byte[]{']'}, output.poll().take());
    }

    @Test
    void cancellationDiscardsPendingAndFutureWritesWithoutRetainingBuffers() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var output = new SegmentedJsonOutput<>(1, 1, cancelled::get, ByteArrayAllocator.INSTANCE);
        output.beginUnit(0);
        output.write('a');
        cancelled.set(true);
        output.write('b');
        output.write(new byte[]{1, 2}, 0, 2);
        output.seal();
        assertNull(output.poll());
        assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[0], 0, 1));
        assertTrue(cancelled.get());
    }
}
