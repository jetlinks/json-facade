package org.jetlinks.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.spi.JsonConfiguration;
import org.jetlinks.json.spi.JsonModule;
import org.jetlinks.json.spi.JsonModuleContext;
import org.jetlinks.json.spi.JsonNativeExtension;
import org.jetlinks.json.spi.JsonProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonApiTest {
    @Test
    void structuralEventsValidatePayloadsAndReuseImmutableConstants() {
        for (JsonToken token : List.of(JsonToken.START_ARRAY, JsonToken.END_ARRAY,
                JsonToken.START_OBJECT, JsonToken.END_OBJECT, JsonToken.NULL)) {
            assertSame(JsonEvent.token(token, null), JsonEvent.token(token, null));
            assertThrows(IllegalArgumentException.class, () -> JsonEvent.token(token, "text"));
            assertThrows(IllegalArgumentException.class, () -> new JsonEvent.Token(token, "text"));
        }
        assertSame(JsonEvent.TRUE, JsonEvent.token(JsonToken.BOOLEAN, "true"));
        assertSame(JsonEvent.FALSE, JsonEvent.token(JsonToken.BOOLEAN, "false"));
        assertEquals(JsonEvent.name("x"), JsonEvent.token(JsonToken.FIELD_NAME, "x"));
        assertEquals(JsonEvent.string("x"), JsonEvent.token(JsonToken.STRING, "x"));
        assertEquals(JsonEvent.number("1"), JsonEvent.token(JsonToken.NUMBER, "1"));
        assertThrows(IllegalArgumentException.class, () -> JsonEvent.token(JsonToken.BOOLEAN, "other"));
        assertThrows(NullPointerException.class, () -> new JsonEvent.Token(JsonToken.STRING, null));
        assertNull(JsonEvent.value(null).value());
    }

    @Test
    void typeTokensHaveValueSemanticsAndDoNotExposeArgumentArrays() {
        JsonType<Map<String, List<Integer>>> type = new JsonType<>() { };
        ParameterizedType expected = (ParameterizedType) type.type();
        JsonType<?> constructed = JsonType.mapOf(JsonType.of(String.class), JsonType.listOf(JsonType.of(Integer.class)));
        assertEquals(expected, constructed.type());
        assertEquals(expected.hashCode(), constructed.type().hashCode());
        ParameterizedType generic = (ParameterizedType) constructed.type();
        Type[] arguments = generic.getActualTypeArguments();
        arguments[0] = Object.class;
        assertEquals(String.class, generic.getActualTypeArguments()[0]);
        assertEquals(Map.class, generic.getRawType());
        assertNull(generic.getOwnerType());
        assertNotEquals(String.class, generic);
        assertNotEquals(JsonType.listOf(JsonType.of(Long.class)).type(),
                JsonType.listOf(JsonType.of(Integer.class)).type());
        assertEquals(type.type(), JsonType.of(type.type()).type());
        JsonType<String> indirect = new TextType() { };
        assertThrows(IllegalArgumentException.class, indirect::type);
    }

    @Test
    void treesDistinguishMissingNullAndIsolateCopies() {
        JsonValue tree = JsonValue.object().put("a/b", JsonValue.array().add(null).add(2));
        assertTrue(tree.get("a/b").get(0).isNull());
        assertTrue(tree.get("missing").isMissing());
        assertFalse(tree.isNull());
        assertFalse(JsonValue.NULL.isMissing());
        assertSame(JsonValue.NULL, JsonValue.of(null));
        assertSame(JsonValue.MISSING, JsonValue.MISSING.deepCopy());
        assertSame(tree, JsonValue.of(tree));
        assertEquals(2, tree.at(JsonPointer.of("/a~1b/1")).toJavaValue());
        assertSame(tree, tree.at(JsonPointer.of("")));
        JsonValue copy = tree.deepCopy();
        copy.get("a/b").set(1, 3).add("tail");
        assertEquals(2, tree.get("a/b").get(1).toJavaValue());
        assertEquals(3, copy.get("a/b").size());
        copy.remove("a/b");
        assertEquals(0, copy.size());
        assertEquals(0, JsonValue.of("scalar").size());
        assertSame(JsonValue.MISSING, tree.get(-1));
        assertSame(JsonValue.MISSING, tree.get("a/b").get(3));
        for (String path : List.of("/a~1b/01", "/a~1b/-", "/a~1b/999999999999999999999", "/absent/x")) {
            assertTrue(tree.at(JsonPointer.of(path)).isMissing(), path);
        }
        assertThrows(IllegalStateException.class, JsonValue.MISSING::toJavaValue);
        assertThrows(IllegalStateException.class, () -> JsonValue.NULL.add(1));
        assertThrows(IllegalStateException.class, () -> JsonValue.of(1).put("x", 1));
        assertEquals(List.of("~", "/", ""), JsonPointer.of("/~0/~1/").segments());
        for (String path : List.of("x", "/~", "/~2")) {
            assertThrows(IllegalArgumentException.class, () -> JsonPointer.of(path));
        }
    }

    @Test
    void operationOptionsValidateTheirOwnBoundaries() {
        for (int index = 0; index < 4; index++) {
            int[] limits = {1, 1, 1, 1};
            limits[index] = 0;
            assertThrows(IllegalArgumentException.class,
                    () -> new JsonOptions(true, false, false, false, false,
                            limits[0], limits[1], limits[2], limits[3]));
        }
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(
                JsonReadOptions.Mode.SINGLE_VALUE, null, JsonReadOptions.NullPolicy.FAIL, 0));
        assertThrows(IllegalArgumentException.class, () -> new JsonReadOptions(
                JsonReadOptions.Mode.NDJSON, new JsonReadOptions.Selection(JsonPointer.of(""), false),
                JsonReadOptions.NullPolicy.FAIL, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new JsonWriteOptions(JsonWriteOptions.Format.ARRAY, 1, 0));
        assertEquals(8192, JsonWriteOptions.of(JsonWriteOptions.Format.ARRAY).chunkSize());
        assertEquals(JsonOptions.DEFAULT.maxValueBytes(), JsonReadOptions.of(JsonReadOptions.Mode.NDJSON).maxValueBytes());
        assertThrows(NullPointerException.class, () -> new JsonReadOptions(null, null, JsonReadOptions.NullPolicy.FAIL, 1));
    }

    @Test
    void configurationCopiesListsAndBuildRejectsMismatchAndDuplicateModules() {
        List<JsonModule> modules = new ArrayList<>();
        JsonConfiguration configuration = new JsonConfiguration(JsonOptions.DEFAULT, modules, List.of());
        modules.add(module("one"));
        assertTrue(configuration.modules().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> configuration.modules().add(module("two")));
        JsonProvider provider = new JsonProvider() {
            @Override
            public String id() {
                return "fixture";
            }

            @Override
            public JsonMapper create(JsonConfiguration input) {
                assertEquals(JsonOptions.DEFAULT, input.options());
                throw new Selected();
            }
        };
        assertThrows(Selected.class, () -> Json.builder().provider(provider).build());
        assertThrows(JsonException.class, () -> Json.builder().provider(provider)
                .module(module("same")).module(module("same")).build());
        JsonNativeExtension extension = () -> "other";
        assertThrows(JsonException.class, () -> Json.builder().provider(provider).extension(extension).build());
        assertThrows(Selected.class, () -> Json.builder().provider(provider).extension(() -> "fixture").build());
        assertThrows(JsonException.class, () -> Json.builder().provider("absent")
                .classLoader(getClass().getClassLoader()).build());
        assertThrows(JsonException.class, () -> Json.builder().build());
        assertThrows(NullPointerException.class, () -> Json.builder().options(null));
        JsonException cause = new JsonException(JsonException.Kind.IO, "code", new java.io.IOException());
        assertEquals(JsonException.Kind.IO, cause.kind());
        assertInstanceOf(java.io.IOException.class, cause.getCause());
    }

    private static JsonModule module(String id) {
        return new JsonModule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void configure(JsonModuleContext context) {
            }
        };
    }

    private static final class Selected extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static class TextType extends JsonType<String> {
    }
}
