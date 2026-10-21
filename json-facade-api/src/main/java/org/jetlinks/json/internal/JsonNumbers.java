package org.jetlinks.json.internal;

import org.jetlinks.json.JsonException;

/** Stateless JSON-number validation shared by token writers; arbitrary raw fragments are rejected.
 * The scan checks grammar without converting the value, preserving precision and numeric text.
 */
public final class JsonNumbers {
    private JsonNumbers() {
    }

    public static void validate(String text) {
        if (!matches(text)) {
            throw new JsonException(JsonException.Kind.SYNTAX, "Invalid JSON number");
        }
    }

    private static boolean matches(String text) {
        int length = text.length();
        int index = length > 0 && text.charAt(0) == '-' ? 1 : 0;
        if (index == length) {
            return false;
        }
        // A zero consumes the entire integer part; subsequent digits cannot be leading zeroes.
        char first = text.charAt(index++);
        if (first >= '1' && first <= '9') {
            index = digitsEnd(text, index);
        } else if (first != '0') {
            return false;
        }
        if (index < length && text.charAt(index) == '.') {
            int start = ++index;
            index = digitsEnd(text, index);
            if (index == start) {
                return false;
            }
        }
        if (index < length && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            index = exponentEnd(text, index + 1);
        }
        return index == length;
    }

    private static int exponentEnd(String text, int index) {
        if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            index++;
        }
        int end = digitsEnd(text, index);
        return end == index ? -1 : end;
    }

    private static int digitsEnd(String text, int index) {
        while (index < text.length()) {
            // JSON digits are ASCII; Character.isDigit would also admit non-JSON numerals.
            char digit = text.charAt(index);
            if (digit < '0' || digit > '9') {
                break;
            }
            index++;
        }
        return index;
    }
}
