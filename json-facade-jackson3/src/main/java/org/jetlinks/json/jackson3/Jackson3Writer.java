package org.jetlinks.json.jackson3;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationContext;
import org.jetlinks.json.internal.JsonNumbers;
import org.jetlinks.json.internal.JsonValues;
import org.jetlinks.json.JsonWriter;

/** Thin generator cursor; caller ownership and no auto-completion are configured by the provider. */
final class Jackson3Writer implements JsonWriter {
    private JsonGenerator generator;
    private ObjectWriter valueWriter;
    private SerializationContext context;

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
        try {
            generator().writeStartObject();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void endObject() {
        try {
            generator().writeEndObject();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void beginArray() {
        try {
            generator().writeStartArray();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void endArray() {
        try {
            generator().writeEndArray();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void name(String name) {
        try {
            generator().writeName(name);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void value(Object value) {
        try {
            JsonGenerator output = generator();
            if (valueWriter != null) {
                // Per-value identity/context lifetime stays bounded while the native generator is reused.
                valueWriter.writeValue(output, JsonValues.bindingValue(value));
            } else {
                java.util.Objects.requireNonNull(context).writeValue(output, JsonValues.bindingValue(value));
            }
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void number(String value) {
        JsonNumbers.validate(value);
        try {
            generator().writeNumber(value);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void string(String value) {
        java.util.Objects.requireNonNull(value, "value");
        try {
            generator().writeString(value);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void bool(boolean value) {
        try {
            generator().writeBoolean(value);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void nullValue() {
        try {
            generator().writeNull();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    public void flush() {
        try {
            generator().flush();
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
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
            } catch (RuntimeException error) {
                throw Jackson3Support.failure(error);
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
