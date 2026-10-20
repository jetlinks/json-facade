package org.jetlinks.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTreeApiTest {
    @Test
    void staticTreesNavigateWithoutDiscoveringProvidersAndRequireExplicitBindingForPresentValues() {
        JsonValue root = JsonValue.object().put("n", 1).put("null", null);
        assertEquals(JsonException.Kind.CONFIGURATION,
                assertThrows(JsonException.class, () -> root.get("n").asInteger()).kind());
        assertNull(root.get("absent").getArray("items").get(0).asInteger());
        assertNull(root.get("null").asInteger());
        assertEquals(0, JsonValue.NULL.asInt());
        assertThrows(NullPointerException.class, () -> root.get("absent").as((JsonType<Object>) null));
        assertEquals("$", JsonValue.NULL.displayPath());
        assertEquals("", JsonValue.NULL.pointer().toString());
        assertEquals("/a~1b/~0/", new JsonPointer(List.of("a/b", "~", "")).toString());
        assertEquals("[\"$\"]", root.get("$").displayPath());
        assertEquals("[\"1\"]", root.get("1").displayPath());
        assertEquals("[\"a-b\"]", root.get("a-b").displayPath());
        assertEquals("node", root.get("node").displayPath());
        assertTrue(root.get("n").get("child").isMissing());
    }

    @Test
    void failureMetadataSerializesAndContextEnrichmentKeepsTheOriginalCauseAndStack() throws Exception {
        IOException cause = new IOException("io");
        JsonException original = new JsonException(JsonException.Kind.IO, "operation", cause);
        original.addSuppressed(new IllegalStateException("cleanup"));
        JsonException contextual = original.at(JsonPointer.of("/a"), "a");
        assertSame(cause, contextual.getCause());
        assertSame(original.getSuppressed()[0], contextual.getSuppressed()[0]);
        assertEquals(original.getStackTrace()[0], contextual.getStackTrace()[0]);
        assertNull(original.pointer());
        assertEquals("/a", contextual.pointer().toString());
        assertEquals(JsonFailure.Reason.UNSPECIFIED, contextual.reason());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(contextual);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            JsonException restored = (JsonException) input.readObject();
            assertEquals(contextual.pointer(), restored.pointer());
            assertEquals("a", restored.displayPath());
            assertEquals(JsonException.Kind.IO, restored.kind());
            assertEquals("io", restored.getCause().getMessage());
        }
        JsonException indexed = new JsonException(new JsonFailure(JsonException.Kind.BINDING,
                JsonFailure.Reason.CONVERSION, "bad", JsonPointer.of("/0"), "[0]", cause));
        assertEquals("a[0]", indexed.at(JsonPointer.of("/a"), "a").displayPath());
        assertEquals("[0]", indexed.at(JsonPointer.of(""), "$").displayPath());
        JsonException root = new JsonException(new JsonFailure(JsonException.Kind.BINDING,
                JsonFailure.Reason.UNSPECIFIED, "bad", JsonPointer.of(""), "$", null));
        assertEquals("a", root.at(JsonPointer.of("/a"), "a").displayPath());
        assertEquals(JsonFailure.Reason.CONVERSION, root.at(JsonPointer.of("/a"), "a").reason());
    }
}
