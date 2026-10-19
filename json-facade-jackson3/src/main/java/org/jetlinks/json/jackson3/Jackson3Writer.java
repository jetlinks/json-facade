package org.jetlinks.json.jackson3;

import tools.jackson.core.JsonGenerator;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriter;

/** Thin generator cursor; caller ownership and no auto-completion are configured by the provider. */
final class Jackson3Writer implements JsonWriter {
    private final JsonGenerator generator;

    Jackson3Writer(JsonGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void beginObject() {
        Jackson3Support.call(() -> {
            generator.writeStartObject();
            return null;
        });
    }

    @Override
    public void endObject() {
        Jackson3Support.call(() -> {
            generator.writeEndObject();
            return null;
        });
    }

    @Override
    public void beginArray() {
        Jackson3Support.call(() -> {
            generator.writeStartArray();
            return null;
        });
    }

    @Override
    public void endArray() {
        Jackson3Support.call(() -> {
            generator.writeEndArray();
            return null;
        });
    }

    @Override
    public void name(String name) {
        Jackson3Support.call(() -> {
            generator.writeName(name);
            return null;
        });
    }

    @Override
    public void value(Object value) {
        Jackson3Support.call(() -> {
            generator.writePOJO(value instanceof JsonValue node ? node.toJavaValue() : value);
            return null;
        });
    }

    @Override
    public void flush() {
        Jackson3Support.call(() -> {
            generator.flush();
            return null;
        });
    }

    @Override
    public void close() {
        Jackson3Support.call(() -> {
            generator.close();
            return null;
        });
    }
}
