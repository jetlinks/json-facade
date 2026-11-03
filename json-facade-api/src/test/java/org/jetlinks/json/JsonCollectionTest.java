package org.jetlinks.json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import org.jetlinks.json.internal.JsonValues;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCollectionTest {
    @Test
    void rawCollectionsAndNodeAccessHaveDistinctContracts() {
        JsonObject object = JsonValue.object();
        Map<String, Object> map = object;
        assertNull(map.put("n", 1));
        assertEquals(1, map.put("n", 2));
        assertEquals(2, map.get("n"));
        map.put("nil", null);
        assertTrue(map.containsKey("nil"));
        assertFalse(map.containsKey("missing"));
        assertNull(map.get("missing"));
        assertTrue(object.node("nil").isNull());
        assertTrue(object.node("missing").isMissing());
        assertNull(object.getArray("nil"));
        assertNull(object.getObject("missing"));
        assertEquals(2, map.remove("n"));
        JsonArray array = JsonValue.array();
        List<Object> list = array;
        assertTrue(list.add(null));
        list.add(0, 1);
        assertEquals(1, list.set(0, 2));
        assertEquals(Arrays.asList(2, null), list);
        assertEquals(list, Arrays.asList(2, null));
        assertEquals(list.hashCode(), Arrays.asList(2, null).hashCode());
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(9));
        assertTrue(array.node(9).isMissing());
        assertEquals("/9", array.node(9).pointer().toString());
    }

    @Test
    void collectionViewsAndDefaultMethodsShareChangesAndReferenceIdentity() {
        JsonObject object = JsonValue.object();
        JsonObject child = JsonValue.object();
        JsonArray array = JsonValue.array();
        object.put("rows", array);
        array.add(child);
        child.put("id", 1);
        assertSame(array, object.get("rows"));
        assertSame(child, array.get(0));
        assertEquals(Map.of("rows", List.of(Map.of("id", 1))), object);
        assertEquals(object, Map.of("rows", List.of(Map.of("id", 1))));
        object.compute("count", (key, old) -> 2);
        object.merge("count", 3, (old, next) -> (Integer) old + (Integer) next);
        assertEquals(5, object.get("count"));
        object.entrySet().stream().filter(entry -> entry.getKey().equals("count"))
                .findFirst().orElseThrow().setValue(6);
        assertEquals(6, object.get("count"));
        assertTrue(object.values().remove(6));
        array.addAll(List.of(2, 3));
        ListIterator<Object> iterator = array.listIterator();
        assertSame(child, iterator.next());
        iterator.set(1);
        iterator.add(4);
        array.subList(1, 2).clear();
        array.removeIf(value -> value.equals(2));
        assertEquals(List.of(1, 3), array);
        array.replaceAll(value -> (Integer) value * 2);
        array.sort((left, right) -> Integer.compare((Integer) right, (Integer) left));
        assertEquals(List.of(6, 2), array);
        JsonObject copy = object.deepCopy();
        array.add(9);
        assertEquals(List.of(6, 2), copy.get("rows"));
    }

    @Test
    void borrowedCollectionsCloseEveryNestedAndDefaultMutationPath() {
        Map<String, Object> child = new LinkedHashMap<>(Map.of("n", 1));
        List<Object> rows = new ArrayList<>(List.of(child));
        Map<String, Object> source = new LinkedHashMap<>(Map.of("rows", rows));
        JsonObject object = JsonValues.borrowObject(source, null);
        JsonArray array = (JsonArray) object.get("rows");
        assertThrows(UnsupportedOperationException.class, () -> object.putIfAbsent("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> object.compute("rows", (key, old) -> {
            throw new AssertionError("Read-only callbacks must not run");
        }));
        assertThrows(UnsupportedOperationException.class, () -> object.entrySet().iterator().next().setValue(1));
        assertThrows(UnsupportedOperationException.class, () -> object.keySet().remove("rows"));
        assertThrows(UnsupportedOperationException.class, () -> object.values().clear());
        List<Object> nestedPaths = List.of(array.get(0), array.toArray()[0], array.stream().findFirst().orElseThrow(),
                array.listIterator().next(), array.subList(0, 1).get(0));
        for (Object path : nestedPaths) {
            assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) path).clear());
        }
        Iterator<Object> iterator = array.iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> array.subList(0, 1).clear());
        assertThrows(UnsupportedOperationException.class, () -> array.replaceAll(value -> value));
        assertThrows(UnsupportedOperationException.class, () -> array.removeIf(value -> false));
        assertThrows(UnsupportedOperationException.class, () -> array.addAll(List.of()));
        assertEquals(Map.of("rows", List.of(Map.of("n", 1))), source);
        child.put("n", 2);
        assertEquals(2, ((Map<?, ?>) array.get(0)).get("n"));
        assertEquals(source, object);
        assertEquals(source.hashCode(), object.hashCode());
    }
}
