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
    private JsonGenerator generator;
    private ObjectWriter valueWriter;
    private SerializerProvider context;

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
            generator().writeStartObject();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void endObject() {
        try {
            generator().writeEndObject();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void beginArray() {
        try {
            generator().writeStartArray();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void endArray() {
        try {
            generator().writeEndArray();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void name(String name) {
        try {
            generator().writeFieldName(name);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void value(Object value) {
        try {
            JsonGenerator output = generator();
            // Re-enter the current operation's context, not the codec's shared mapper configuration.
            if (valueWriter != null) {
                // Reuse generator buffers, but bound each value in a fresh context: object IDs must not retain a stream.
                valueWriter.writeValue(output, JsonValues.bindingValue(value));
            } else {
                java.util.Objects.requireNonNull(context).defaultSerializeValue(JsonValues.bindingValue(value), output);
            }
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void number(String value) {
        JsonNumbers.validate(value);
        try {
            generator().writeNumber(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void string(String value) {
        java.util.Objects.requireNonNull(value, "value");
        try {
            generator().writeString(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void bool(boolean value) {
        try {
            generator().writeBoolean(value);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void nullValue() {
        try {
            generator().writeNull();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void flush() {
        try {
            generator().flush();
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    public void close() {
        // Detach before native close: a failing flush must not pin the target or binding context.
        JsonGenerator owned = generator;
        generator = null;
        valueWriter = null;
        context = null;
        if (owned != null) {
            try {
                owned.close();
            } catch (IOException | RuntimeException error) {
                throw Jackson2Support.failure(error);
            }
        }
    }

    private JsonGenerator generator() {
        if (generator == null) {
            throw new IllegalStateException("JSON writer is closed");
        }
        return generator;
    }
}
