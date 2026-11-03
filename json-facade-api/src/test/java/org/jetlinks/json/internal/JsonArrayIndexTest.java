package org.jetlinks.json.internal;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonArrayIndexTest {
    @Test
    void canonicalIndicesIncludeTheFullNonnegativeLongRange() {
        for (long index : new long[]{0, 1, 9, 10, Integer.MAX_VALUE,
                (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            assertEquals(index, JsonArrayIndex.parse(Long.toString(index)));
        }
    }

    @Test
    void invalidAndOverflowingSegmentsNeverBecomeArrayIndices() {
        for (String segment : List.of("", "00", "01", "-1", "+1", "1.0", "1e1", " 1", "1 ",
                "١", "a", "/", "9223372036854775808", "9999999999999999999", "10000000000000000000")) {
            assertEquals(-1, JsonArrayIndex.parse(segment), segment);
        }
    }
}
