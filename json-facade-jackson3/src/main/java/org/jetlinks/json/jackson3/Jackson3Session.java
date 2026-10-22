package org.jetlinks.json.jackson3;

import java.io.IOException;
import java.nio.ByteBuffer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.async.ByteBufferFeeder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.util.TokenBuffer;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.AbstractReadSession;

/** Jackson async tokenizer and record-local native binding; shared code owns framing and selection. */
final class Jackson3Session<T> extends AbstractReadSession<T> {
    private JsonParser parser;
    private ByteBufferFeeder feeder;
    private ObjectReader reader;
    private TokenBuffer record;
    private boolean exhausted;

    Jackson3Session(JsonMapper mapper, ObjectMapper nativeMapper, ObjectReader preparedReader, JsonType<T> type,
                    JsonReadOptions options) throws tools.jackson.core.JacksonException {
        this(mapper, nativeMapper, preparedReader, type, options, false);
    }

    Jackson3Session(JsonMapper mapper, ObjectMapper nativeMapper, ObjectReader preparedReader, JsonType<T> type,
                    JsonReadOptions options, boolean events) throws tools.jackson.core.JacksonException {
        super(options, mapper.options().maxValueBytes(), events);
        reader = preparedReader.forType(nativeMapper.constructType(type.type()));
        parser = reader.createNonBlockingByteBufferParser();
        feeder = (ByteBufferFeeder) parser.nonBlockingInputFeeder();
    }

    @Override
    protected Event nextEvent() {
        // Avoid a captured callback in the per-token path; error classification stays shared.
        try {
            tools.jackson.core.JsonToken nativeToken = parser.nextToken();
            exhausted = nativeToken == null;
            JsonToken token = Jackson3Reader.token(nativeToken);
            String name = token == JsonToken.FIELD_NAME && tracksPath() ? parser.getString() : null;
            var start = parser.currentTokenLocation();
            var end = parser.currentLocation();
            return event(token, name, start.getByteOffset(), end.getByteOffset(),
                    start.getLineNr(), end.getLineNr());
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T bindEvent(JsonToken token) {
        try {
            String text = switch (token) {
                case FIELD_NAME, STRING, NUMBER, BOOLEAN -> parser.getString();
                default -> null;
            };
            // Only the eventSession factory enables this branch, with T fixed to JsonEvent.
            return (T) JsonEvent.token(token, text);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    protected void startRecord() {
        record = TokenBuffer.forBuffering(parser, parser.objectReadContext());
    }

    @Override
    protected void appendToken() {
        try {
            if (parser.currentToken().isNumeric()) {
                // Delay number conversion until binding, retaining text for String/custom readers.
                record.writeNumber(parser.getString(),
                        parser.currentToken() == tools.jackson.core.JsonToken.VALUE_NUMBER_INT);
            } else {
                record.copyCurrentEvent(parser);
            }
        } catch (IOException | RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    protected T bindRecord() {
        TokenBuffer complete = record;
        record = null;
        try (complete) {
            return reader.readValue(complete);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    protected boolean exhausted() {
        return exhausted;
    }

    @Override
    protected boolean parserNeedsInput() {
        return feeder.needMoreInput();
    }

    @Override
    protected void feedParser(ByteBuffer input) {
        try {
            feeder.feedInput(input);
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }

    @Override
    protected void finishParser() {
        feeder.endOfInput();
    }

    @Override
    protected void closeParser() {
        // Native close can retain its last ByteBuffer; detach even if cleanup throws.
        JsonParser owned = parser;
        TokenBuffer pending = record;
        parser = null;
        feeder = null;
        reader = null;
        record = null;
        try (owned) {
            if (pending != null) {
                pending.close();
            }
        } catch (RuntimeException error) {
            throw Jackson3Support.failure(error);
        }
    }
}
