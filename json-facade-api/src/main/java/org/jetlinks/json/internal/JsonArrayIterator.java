package org.jetlinks.json.internal;

import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.jetlinks.json.CloseableIterator;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReader;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;

/** Root-array framing over an existing cursor; native parsing and binding stay with the Provider.
 * Created per operation, with no parser or input access until the first hasNext/next call.
 * Only the current token is held; a bound element is returned directly without a result wrapper.
 * @param <T> bound element type
 * @since 1.0.0
 */
public final class JsonArrayIterator<T> implements CloseableIterator<T> {
    private JsonMapper mapper;
    private InputStream input;
    private JsonType<T> type;
    private JsonReader reader;
    private boolean ready;
    private boolean closed;

    public JsonArrayIterator(JsonMapper mapper, InputStream input, JsonType<T> type) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.input = Objects.requireNonNull(input, "input");
        this.type = Objects.requireNonNull(type, "type");
        type.type();
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            return false;
        }
        if (ready) {
            return true;
        }
        try {
            if (reader == null) {
                reader = mapper.reader(input);
                mapper = null;
                input = null;
                if (reader.next() != JsonToken.START_ARRAY) {
                    throw new JsonException(JsonException.Kind.SYNTAX, "Expected a root array");
                }
            }
            JsonToken token = reader.next();
            if (token == JsonToken.END_ARRAY) {
                // Checking EOF is part of full consumption; early close must not drain the source.
                if (reader.next() != null) {
                    throw new JsonException(JsonException.Kind.SYNTAX, "Unexpected trailing value");
                }
                close();
                return false;
            }
            if (token == null) {
                throw new JsonException(JsonException.Kind.SYNTAX, "Incomplete root array");
            }
            ready = true;
            return true;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        try {
            T value = reader.readValue(type);
            ready = false;
            return value;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ready = false;
        mapper = null;
        input = null;
        type = null;
        JsonReader owned = reader;
        reader = null;
        if (owned != null) {
            owned.close();
        }
    }

    private void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error cleanup) {
            // Preserve the parse/binding failure even if native cleanup also fails.
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
