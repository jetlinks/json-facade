package org.jetlinks.json;

import java.util.ArrayList;
import java.util.List;

/** RFC 6901 pointer, with decoded immutable segments. An empty pointer selects the root.
 * @since 1.0.0
 */
public record JsonPointer(List<String> segments) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public JsonPointer {
        segments = List.copyOf(segments);
    }

    public static JsonPointer of(String pointer) {
        if (pointer.isEmpty()) {
            return new JsonPointer(List.of());
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("JSON Pointer must be empty or start with /");
        }
        List<String> result = new ArrayList<>();
        for (String segment : pointer.substring(1).split("/", -1)) {
            result.add(decode(segment));
        }
        return new JsonPointer(result);
    }

    /** Canonical RFC 6901 encoding, including escaping of literal '~' and '/'. */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            result.append('/').append(segment.replace("~", "~0").replace("/", "~1"));
        }
        return result.toString();
    }

    private static String decode(String segment) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '~') {
                if (++i >= segment.length()) {
                    throw new IllegalArgumentException("Incomplete JSON Pointer escape");
                }
                char escape = segment.charAt(i);
                if (escape != '0' && escape != '1') {
                    throw new IllegalArgumentException("Invalid JSON Pointer escape");
                }
                c = escape == '0' ? '~' : '/';
            }
            result.append(c);
        }
        return result.toString();
    }
}
