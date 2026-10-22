package org.jetlinks.json;

import java.util.ArrayList;
import java.util.List;

/**
 * 不可变的 RFC 6901 Pointer，空 Pointer 表示根；不支持 JSONPath 表达式。
 * 例如 {@code JsonPointer.of("/data/items/0")}；字段中的 {@code ~} 和 {@code /} 须转义。
 *
 * @param segments 已解码的非空路径段列表，构造时防御性复制，元素不可为 null
 * @since 1.0.0
 */
public record JsonPointer(List<String> segments) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public JsonPointer {
        segments = List.copyOf(segments);
    }

    /**
     * 解析 RFC 6901 字符串；须为空或以 {@code /} 开头，仅接受 {@code ~0} 和 {@code ~1} 转义。
     */
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

    /**
     * 返回规范 RFC 6901 表示，转义字面量 {@code ~} 和 {@code /}。
     */
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
