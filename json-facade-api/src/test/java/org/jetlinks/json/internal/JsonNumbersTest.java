package org.jetlinks.json.internal;

import java.util.List;
import java.util.regex.Pattern;
import org.jetlinks.json.JsonException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonNumbersTest {
    @Test
    void acceptsJsonGrammarWithoutConvertingOrLimitingNumericRange() {
        for (String text : List.of("0", "-0", "1", "-1", "9", "10", "-1234567890", "0.0", "-0.001",
                "1.2300E+2", "0e0", "0E-0", "1e+9", "-12345.6700e+8",
                "123456789012345678901234567890", "1e99999999999999999999999")) {
            assertDoesNotThrow(() -> JsonNumbers.validate(text), text);
        }
    }

    @Test
    void rejectsNonJsonNumbersAndRawFragmentsWithTheExistingFailure() {
        for (String text : List.of("", "-", "+0", "01", "-01", "00", "1.", "1.e2", ".1", "-.1",
                "1e", "1E", "1e+", "1E-", "0e--1", "1e1e1", "--1", "1+2", "1,2", "1]", "1 true",
                "NaN", "Infinity", "-Infinity", "0x1", "1_000", "1f", " 1", "1 ", "1\n", "\n1",
                "١", "１", "−1", "1.١", "1e１", "1\u0000")) {
            JsonException failure = assertThrows(JsonException.class, () -> JsonNumbers.validate(text), text);
            assertEquals(JsonException.Kind.SYNTAX, failure.kind(), text);
            assertEquals("Invalid JSON number", failure.getMessage(), text);
        }
        assertThrows(NullPointerException.class, () -> JsonNumbers.validate(null));
    }

    @Test
    void scansLongNumbersAndRejectsInvalidSuffixes() {
        String digits = "7".repeat(100000);
        assertDoesNotThrow(() -> JsonNumbers.validate(digits));
        assertDoesNotThrow(() -> JsonNumbers.validate("-0." + digits + "E+" + digits));
        for (String suffix : List.of("x", ".", "e", "e+", "e-")) {
            assertThrows(JsonException.class, () -> JsonNumbers.validate(digits + suffix), suffix);
        }
    }

    @Test
    void matchesThePreviousGrammarAcrossShortTokenCombinations() {
        Pattern reference = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
        char[] alphabet = "01-+.eE ".toCharArray();
        for (int length = 0; length <= 5; length++) {
            for (int value = 0; value < 1 << (3 * length); value++) {
                char[] chars = new char[length];
                int remaining = value;
                for (int index = 0; index < length; index++) {
                    chars[index] = alphabet[remaining & 7];
                    remaining >>>= 3;
                }
                String text = new String(chars);
                assertEquals(reference.matcher(text).matches(), accepts(text), text);
            }
        }
    }

    private static boolean accepts(String text) {
        try {
            JsonNumbers.validate(text);
            return true;
        } catch (JsonException invalid) {
            return false;
        }
    }
}
