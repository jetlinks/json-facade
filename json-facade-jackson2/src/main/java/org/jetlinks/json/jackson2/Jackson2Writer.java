package org.jetlinks.json.jackson2;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.jetlinks.json.internal.JsonNumbers;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.JsonWriter;

/** Thin generator cursor; caller ownership and no auto-completion are configured by the provider. */
final class Jackson2Writer implements JsonWriter {
    private final JsonGenerator generator;
    private final ObjectWriter valueWriter;
    private final SerializerProvider context;

    Jackson2Writer(JsonGenerator generator, SerializerProvider context) {
        this.generator = generator;
        this.context = context;
        valueWriter = null;
    }

    Jackson2Writer(JsonGenerator generator, ObjectWriter valueWriter) {
        this.generator = generator;
        this.valueWriter = valueWriter;
        context = null;
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
            // Re-enter the current operation's context, not the codec's shared mapper configuration.
            if (valueWriter != null) {
                // Reuse generator buffers, but bound each value in a fresh context: object IDs must not retain a stream.
                valueWriter.writeValue(generator, JsonValues.bindingValue(value));
            } else {
                java.util.Objects.requireNonNull(context).defaultSerializeValue(JsonValues.bindingValue(value), generator);
            }
            return null;
        });
    }

    @Override
    public void number(String value) {
        JsonNumbers.validate(value);
        Jackson2Support.call(() -> {
            generator.writeNumber(value);
            return null;
        });
    }

    @Override
    public void string(String value) {
        java.util.Objects.requireNonNull(value, "value");
        Jackson2Support.call(() -> {
            generator.writeString(value);
            return null;
        });
    }

    @Override
    public void bool(boolean value) {
        Jackson2Support.call(() -> {
            generator.writeBoolean(value);
            return null;
        });
    }

    @Override
    public void nullValue() {
        Jackson2Support.call(() -> {
            generator.writeNull();
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
