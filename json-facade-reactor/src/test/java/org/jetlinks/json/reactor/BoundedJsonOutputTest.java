package org.jetlinks.json.reactor;

import java.nio.charset.StandardCharsets;
import org.jetlinks.json.JsonException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedJsonOutputTest {
    @Test
    void bulkWritesAllowTheExactLimitAndRejectAnOversizedAppendBeforeWriting() {
        BoundedJsonOutput output = new BoundedJsonOutput(5);
        byte[] record = "\"中\"".getBytes(StandardCharsets.UTF_8);
        output.write(record, 0, record.length);
        output.write(record, 0, 0);
        assertArrayEquals(record, output.bytes());
        assertEquals(JsonException.Kind.LIMIT,
                assertThrows(JsonException.class, () -> output.write('!')).kind());
        assertArrayEquals(record, output.bytes());
        BoundedJsonOutput partial = new BoundedJsonOutput(5);
        partial.write('[');
        assertThrows(JsonException.class, () -> partial.write(record, 0, record.length));
        assertArrayEquals(new byte[]{'['}, partial.bytes());
    }
}
