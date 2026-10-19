package org.jetlinks.json;

/** Engine-neutral JSON tokens; end-of-input is returned as Java null by JsonReader.next().
 * @since 1.0.0
 */
public enum JsonToken {
    START_OBJECT, END_OBJECT, START_ARRAY, END_ARRAY, FIELD_NAME,
    STRING, NUMBER, BOOLEAN, NULL
}
