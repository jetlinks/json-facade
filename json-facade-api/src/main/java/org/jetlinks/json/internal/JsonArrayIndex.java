package org.jetlinks.json.internal;

/** Canonical nonnegative decimal indices shared by tree access and streaming selection.
 * Parsing returns -1 for invalid/overflowing segments; callers retain the original diagnostic segment.
 */
public final class JsonArrayIndex {
    private JsonArrayIndex() {
    }

    public static long parse(String segment) {
        if (segment.isEmpty() || segment.length() > 19
                || segment.length() > 1 && segment.charAt(0) == '0') {
            return -1;
        }
        long index = 0;
        for (int i = 0; i < segment.length(); i++) {
            int digit = segment.charAt(i) - '0';
            if (digit < 0 || digit > 9 || index > (Long.MAX_VALUE - digit) / 10) {
                return -1;
            }
            index = index * 10 + digit;
        }
        return index;
    }
}
