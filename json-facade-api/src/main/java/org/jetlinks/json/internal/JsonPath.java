package org.jetlinks.json.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jetlinks.json.JsonPointer;

/** Immutable access segments, independent of tree storage. Render only for diagnostics/public access. */
public final class JsonPath {
    public static final JsonPath ROOT = new JsonPath(null, null, 0);
    private final JsonPath parent;
    private final String segment;
    private final int position;

    private JsonPath(JsonPath parent, String segment, int position) {
        this.parent = parent;
        this.segment = segment;
        this.position = position;
    }

    public JsonPath field(String name) {
        return new JsonPath(this, Objects.requireNonNull(name, "name"), 0);
    }

    public JsonPath index(int position) {
        return new JsonPath(this, null, position);
    }

    public JsonPointer pointer() {
        List<String> segments = new ArrayList<>();
        for (JsonPath path = this; path.parent != null; path = path.parent) {
            segments.add(path.segment == null ? Integer.toString(path.position) : path.segment);
        }
        Collections.reverse(segments);
        return new JsonPointer(segments);
    }

    public String display() {
        List<JsonPath> segments = new ArrayList<>();
        for (JsonPath path = this; path.parent != null; path = path.parent) {
            segments.add(path);
        }
        Collections.reverse(segments);
        StringBuilder result = new StringBuilder();
        for (JsonPath path : segments) {
            path.appendTo(result);
        }
        return result.isEmpty() ? "$" : result.toString();
    }

    private void appendTo(StringBuilder result) {
        if (segment == null) {
            result.append('[').append(position).append(']');
        } else if (identifier(segment)) {
            if (!result.isEmpty()) {
                result.append('.');
            }
            result.append(segment);
        } else {
            result.append("[\"");
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '\\' || c == '"') {
                    result.append('\\').append(c);
                } else if (c < 32) {
                    result.append("\\u");
                    String hex = Integer.toHexString(c);
                    result.append("0".repeat(4 - hex.length())).append(hex);
                } else {
                    result.append(c);
                }
            }
            result.append("\"]");
        }
    }

    private static boolean identifier(String value) {
        if (value.isEmpty() || value.charAt(0) == '$' || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
