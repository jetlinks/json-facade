package org.jetlinks.json;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonOperationOptionsTest {
    @Test
    void immutableOverlayPreservesInheritanceFalseAndIdentityKeys() {
        JsonAttribute<Integer> key = JsonAttribute.of("offset", Integer.class);
        JsonAttribute<Integer> other = JsonAttribute.of("offset", Integer.class);
        var builder = JsonOperationOptions.builder().pretty(true).omitNulls(true).attribute(key, 1);
        var first = builder.build();
        builder.pretty(false).attribute(key, 2);
        assertTrue(first.pretty());
        assertEquals(1, first.attributes().get(key));
        assertNull(first.attributes().get(other));
        assertEquals("offset", key.toString());
        assertNull(key.cast(null));
        assertThrows(ClassCastException.class, () -> key.cast("wrong"));
        assertThrows(IllegalArgumentException.class, () -> JsonAttribute.of("primitive", int.class));
        assertThrows(NullPointerException.class, () -> builder.attribute(key, null));
        assertThrows(UnsupportedOperationException.class, () -> first.attributes().clear());
        assertSame(first, first.overlay(JsonOperationOptions.EMPTY));
        var merged = first.overlay(builder.build());
        assertFalse(merged.pretty());
        assertTrue(merged.omitNulls());
        assertEquals(2, merged.attributes().get(key));
        assertNull(merged.losslessNumbers());
        assertNull(merged.failUnknownProperties());
        assertNull(merged.failDuplicateKeys());
        assertTrue(JsonOperationOptions.EMPTY.isEmpty());
    }

    @Test
    void nativeCallbacksCheckAllTypesAndPreserveOrderAndFailures() {
        var calls = new AtomicInteger();
        var options = JsonOperationOptions.builder()
                .nativeReader(String.class, value -> {
                    calls.incrementAndGet();
                    return value + "1";
                })
                .nativeReader(String.class, value -> value + "2")
                .nativeWriter(Integer.class, value -> value + 1).build();
        assertThrows(JsonException.class, () -> options.validateNativeTypes("", ""));
        assertEquals(0, calls.get());
        options.validateNativeTypes("", 1);
        assertEquals("12", options.configureReader(String.class, ""));
        assertEquals(2, options.configureWriter(Integer.class, 1));
        assertFalse(options.isEmpty());
        assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class,
                () -> JsonOperationOptions.builder().nativeWriter(String.class, value -> null)
                        .build().configureWriter(String.class, "")).kind());
        assertThrows(JsonException.class, () -> JsonOperationOptions.builder()
                .nativeReader(String.class, value -> { throw new IllegalStateException("callback"); })
                .build().configureReader(String.class, ""));
    }
}
