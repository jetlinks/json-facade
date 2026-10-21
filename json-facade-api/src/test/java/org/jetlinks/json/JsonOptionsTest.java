package org.jetlinks.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonOptionsTest {
    @Test
    void namedCopiesComposeWithoutResettingEarlierSettingsOrMutatingTheSource() {
        JsonOptions defaults = JsonOptions.DEFAULT;
        JsonOptions configured = defaults.withLosslessNumbers(false).withFailUnknownProperties(true)
                .withFailDuplicateKeys(true).withOmitNulls(true).withPretty(true)
                .withMaxDepth(7).withMaxStringLength(11).withMaxNumberLength(13).withMaxValueBytes(17);
        assertEquals(new JsonOptions(false, true, true, true, true, 7, 11, 13, 17), configured);
        assertEquals(configured, configured.withPretty(false).withPretty(true));
        assertEquals(defaults, configured.withMaxValueBytes(defaults.maxValueBytes())
                .withMaxNumberLength(defaults.maxNumberLength()).withMaxStringLength(defaults.maxStringLength())
                .withMaxDepth(defaults.maxDepth()).withPretty(false).withOmitNulls(false)
                .withFailDuplicateKeys(false).withFailUnknownProperties(false).withLosslessNumbers(true));
        assertEquals(new JsonOptions(true, false, false, false, false,
                256, 8 * 1024 * 1024, 1000, 8 * 1024 * 1024), defaults);
    }

    @Test
    void streamCopiesPreserveSelectionFramingAndPreviouslyConfiguredLimits() {
        JsonPointer pointer = JsonPointer.of("/items");
        JsonReadOptions selected = JsonReadOptions.selectElements(pointer);
        JsonReadOptions read = selected.withMaxValueBytes(37).withNullPolicy(JsonReadOptions.NullPolicy.SKIP);
        assertEquals(new JsonReadOptions(JsonReadOptions.Mode.SINGLE_VALUE,
                new JsonReadOptions.Selection(pointer, true), JsonReadOptions.NullPolicy.SKIP, 37), read);
        assertSame(pointer, read.selection().pointer());
        assertEquals(selected, read.withNullPolicy(JsonReadOptions.NullPolicy.FAIL)
                .withMaxValueBytes(JsonOptions.DEFAULT.maxValueBytes()));
        JsonReadOptions whole = JsonReadOptions.selectValue(pointer);
        assertEquals(JsonReadOptions.Mode.SINGLE_VALUE, whole.mode());
        assertSame(pointer, whole.selection().pointer());
        assertFalse(whole.selection().arrayElements());
        assertEquals(JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE).nullPolicy(), whole.nullPolicy());
        assertEquals(JsonOptions.DEFAULT.maxValueBytes(), whole.maxValueBytes());
        JsonWriteOptions original = JsonWriteOptions.of(JsonWriteOptions.Format.NDJSON);
        JsonWriteOptions write = original.withMaxValueBytes(71).withChunkSize(5);
        assertEquals(new JsonWriteOptions(JsonWriteOptions.Format.NDJSON, 5, 71), write);
        assertEquals(original, write.withChunkSize(original.chunkSize()).withMaxValueBytes(original.maxValueBytes()));
    }

    @Test
    void convenienceConfigurationRetainsCanonicalBoundaryValidation() {
        for (int invalid : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> JsonOptions.DEFAULT.withMaxDepth(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonOptions.DEFAULT.withMaxStringLength(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonOptions.DEFAULT.withMaxNumberLength(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonOptions.DEFAULT.withMaxValueBytes(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonReadOptions.of(JsonReadOptions.Mode.NDJSON)
                    .withMaxValueBytes(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)
                    .withChunkSize(invalid));
            assertThrows(IllegalArgumentException.class, () -> JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY)
                    .withMaxValueBytes(invalid));
        }
        assertThrows(NullPointerException.class, () -> JsonReadOptions.selectElements(null));
        assertThrows(NullPointerException.class, () -> JsonReadOptions.selectValue(null));
        assertThrows(NullPointerException.class, () -> JsonReadOptions.of(JsonReadOptions.Mode.ARRAY_ELEMENTS)
                .withNullPolicy(null));
    }
}
