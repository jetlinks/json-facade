package org.jetlinks.json.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jetlinks.json.JsonPointer;

/** Immutable access segments, independent of tree storage. Render only for diagnostics/public access. */
public class JsonPath {
    public static final JsonPath ROOT = new JsonPath(null, null, 0);
    private final JsonPath parent;
    private final String segment;
    private final int position;

    private JsonPath(JsonPath parent, String segment, int position) {
        this.parent = parent;
        this.segment = segment;
        this.position = position;
    }

    /** Snapshot a foreign location without retaining its node or guessing numeric segment types. */
    public static JsonPath location(JsonPointer pointer, String displayPath) {
        return new LocatedRoot(Objects.requireNonNull(pointer, "pointer"),
                Objects.requireNonNull(displayPath, "displayPath"));
    }

    public JsonPath field(String name) {
        return new JsonPath(this, Objects.requireNonNull(name, "name"), 0);
    }

    public JsonPath index(int position) {
        return new JsonPath(this, null, position);
    }

    public JsonPointer pointer() {
        List<String> segments = new ArrayList<>();
        JsonPath path = this;
        for (; path.parent != null; path = path.parent) {
            segments.add(path.segment == null ? Integer.toString(path.position) : path.segment);
        }
        Collections.reverse(segments);
        if (path instanceof LocatedRoot root) {
            segments.addAll(0, root.pointer.segments());
        }
        return new JsonPointer(segments);
    }

    public String display() {
        List<JsonPath> segments = new ArrayList<>();
        JsonPath base = this;
        for (; base.parent != null; base = base.parent) {
            segments.add(base);
        }
        Collections.reverse(segments);
        StringBuilder result = new StringBuilder();
        if (base instanceof LocatedRoot root && !root.display.equals("$")) {
            result.append(root.display);
        }
        for (JsonPath path : segments) {
            path.appendTo(result);
        }
        return result.isEmpty() ? "$" : result.toString();
    }

    /** Foreign prefixes store only immutable diagnostics; ordinary access segments stay compact. */
    private static final class LocatedRoot extends JsonPath {
        private final JsonPointer pointer;
        private final String display;

        LocatedRoot(JsonPointer pointer, String display) {
            super(null, null, 0);
            this.pointer = pointer;
            this.display = display;
        }
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
