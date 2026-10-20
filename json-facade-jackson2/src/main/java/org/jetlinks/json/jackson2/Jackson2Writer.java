package org.jetlinks.json.jackson2;

import java.io.IOException;
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
        try {
            generator.writeStartObject();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void endObject() {
        try {
            generator.writeEndObject();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void beginArray() {
        try {
            generator.writeStartArray();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void endArray() {
        try {
            generator.writeEndArray();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void name(String name) {
        try {
            generator.writeFieldName(name);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void value(Object value) {
        try {
            // Re-enter the current operation's context, not the codec's shared mapper configuration.
            if (valueWriter != null) {
                // Reuse generator buffers, but bound each value in a fresh context: object IDs must not retain a stream.
                valueWriter.writeValue(generator, JsonValues.bindingValue(value));
            } else {
                java.util.Objects.requireNonNull(context).defaultSerializeValue(JsonValues.bindingValue(value), generator);
            }
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void number(String value) {
        JsonNumbers.validate(value);
        try {
            generator.writeNumber(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void string(String value) {
        java.util.Objects.requireNonNull(value, "value");
        try {
            generator.writeString(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void bool(boolean value) {
        try {
            generator.writeBoolean(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void nullValue() {
        try {
            generator.writeNull();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void flush() {
        try {
            generator.flush();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void close() {
        try {
            generator.close();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }
}
