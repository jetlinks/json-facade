package org.jetlinks.json.jackson3;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;
import org.jetlinks.json.internal.JsonNumbers;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.JsonWriter;

/** Thin generator cursor; caller ownership and no auto-completion are configured by the provider. */
final class Jackson3Writer implements JsonWriter {
    private final JsonGenerator generator;
    private final ObjectWriter valueWriter;
    private final SerializationContext context;

    Jackson3Writer(JsonGenerator generator, SerializationContext context) {
        this.generator = generator;
        this.context = context;
        valueWriter = null;
    }

    Jackson3Writer(JsonGenerator generator, ObjectWriter valueWriter) {
        this.generator = generator;
        this.valueWriter = valueWriter;
        context = null;
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
            if (valueWriter != null) {
                // Per-value identity/context lifetime stays bounded while the native generator is reused.
                valueWriter.writeValue(generator, JsonValues.bindingValue(value));
            } else {
                java.util.Objects.requireNonNull(context).writeValue(generator, JsonValues.bindingValue(value));
            }
            return null;
        });
    }

    @Override
    public void number(String value) {
        JsonNumbers.validate(value);
        Jackson3Support.call(() -> {
            generator.writeNumber(value);
            return null;
        });
    }

    @Override
    public void string(String value) {
        java.util.Objects.requireNonNull(value, "value");
        Jackson3Support.call(() -> {
            generator.writeString(value);
            return null;
        });
    }

    @Override
    public void bool(boolean value) {
        Jackson3Support.call(() -> {
            generator.writeBoolean(value);
            return null;
        });
    }

    @Override
    public void nullValue() {
        Jackson3Support.call(() -> {
            generator.writeNull();
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
