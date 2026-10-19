package org.jetlinks.json.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetlinks.json.JsonToken;

/** Builds one selected record only; skipped document subtrees never enter this builder. */
final class ValueBuilder {
    private final Deque<Container> stack = new ArrayDeque<>();
    private Object value;

    void accept(JsonToken token, Object scalar) {
        switch (token) {
            case FIELD_NAME -> stack.peek().name = (String) scalar;
            case START_OBJECT -> start(new LinkedHashMap<String, Object>());
            case START_ARRAY -> start(new ArrayList<>());
            case END_OBJECT, END_ARRAY -> stack.pop();
            default -> append(scalar);
        }
    }

    boolean complete() {
        return stack.isEmpty();
    }

    Object value() {
        return value;
    }

    private void start(Object container) {
        append(container);
        stack.push(new Container(container));
    }

    private void append(Object item) {
        if (stack.isEmpty()) {
            value = item;
        } else {
            stack.peek().add(item);
        }
    }

    private static final class Container {
        private final Object value;
        private String name;

        private Container(Object value) {
            this.value = value;
        }

        @SuppressWarnings("unchecked")
        private void add(Object item) {
            if (value instanceof Map<?, ?>) {
                ((Map<String, Object>) value).put(name, item);
            } else {
                ((List<Object>) value).add(item);
            }
        }
    }
}
