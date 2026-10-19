package org.jetlinks.json.jackson2;

import com.fasterxml.jackson.core.JsonGenerator;
import org.jetlinks.json.JsonValue;
import org.jetlinks.json.JsonWriter;

/** Thin generator cursor; caller ownership and no auto-completion are configured by the provider. */
final class Jackson2Writer implements JsonWriter {
    private final JsonGenerator generator;

    Jackson2Writer(JsonGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void beginObject() {
        Jackson2Support.call(() -> {
            generator.writeStartObject();
            return null;
        });
    }

    @Override
    public void endObject() {
        Jackson2Support.call(() -> {
            generator.writeEndObject();
            return null;
        });
    }

    @Override
    public void beginArray() {
        Jackson2Support.call(() -> {
            generator.writeStartArray();
            return null;
        });
    }

    @Override
    public void endArray() {
        Jackson2Support.call(() -> {
            generator.writeEndArray();
            return null;
        });
    }

    @Override
    public void name(String name) {
        Jackson2Support.call(() -> {
            generator.writeFieldName(name);
            return null;
        });
    }

    @Override
    public void value(Object value) {
        Jackson2Support.call(() -> {
            generator.writeObject(value instanceof JsonValue node ? node.toJavaValue() : value);
            return null;
        });
    }

    @Override
    public void flush() {
        Jackson2Support.call(() -> {
            generator.flush();
            return null;
        });
    }

    @Override
    public void close() {
        Jackson2Support.call(() -> {
            generator.close();
            return null;
        });
    }
}
