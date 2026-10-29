package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.nio.ByteBuffer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.jetlinks.json.JsonEvent;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.AbstractReadSession;

/** Jackson async tokenizer and record-local native binding; shared code owns framing and selection. */
final class Jackson2Session<T> extends AbstractReadSession<T> {
    private JsonParser parser;
    private ByteBufferFeeder feeder;
    private ObjectMapper mapper;
    private ObjectReader reader;
    private TokenBuffer record;
    private boolean exhausted;

    Jackson2Session(JsonMapper mapper, ObjectMapper nativeMapper, ObjectReader preparedReader, JsonType<T> type,
                    JsonReadOptions options) throws IOException {
        this(mapper, nativeMapper, preparedReader, type, options, false);
    }

    Jackson2Session(JsonMapper mapper, ObjectMapper nativeMapper, ObjectReader preparedReader, JsonType<T> type,
                    JsonReadOptions options, boolean events) throws IOException {
        super(options, mapper.options().maxValueBytes(), events);
        this.mapper = nativeMapper;
        reader = preparedReader.forType(nativeMapper.constructType(type.type()));
        parser = preparedReader.getFactory().createNonBlockingByteBufferParser();
        reader.getConfig().initialize(parser);
        feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
    }

    @Override
    protected Event nextEvent() {
        // Avoid a captured callback in the per-token path; error classification stays shared.
        try {
            com.fasterxml.jackson.core.JsonToken nativeToken = parser.nextToken();
            exhausted = nativeToken == null;
            JsonToken token = Jackson2Reader.token(nativeToken);
            String name = token == JsonToken.FIELD_NAME && tracksPath() ? parser.getText() : null;
            var start = parser.currentTokenLocation();
            var end = parser.currentLocation();
            return event(token, name, start.getByteOffset(), end.getByteOffset(),
                    start.getLineNr(), end.getLineNr());
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T bindEvent(JsonToken token) {
        try {
            String text = switch (token) {
                case FIELD_NAME, STRING, NUMBER, BOOLEAN -> parser.getText();
                default -> null;
            };
            // Only the eventSession factory enables this branch, with T fixed to JsonEvent.
            return (T) JsonEvent.token(token, text);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    protected void startRecord() {
        record = new TokenBuffer(parser);
    }

    @Override
    protected void appendToken() {
        try {
            if (parser.currentToken().isNumeric()) {
                // The public TokenBuffer API preserves both the numeric token kind and its lexeme.
                // Binding then chooses Double/BigDecimal/coercion using the reader's configuration.
                record.writeNumber(parser.getText(),
                        parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT);
            } else {
                record.copyCurrentEvent(parser);
            }
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    protected T bindRecord() {
        TokenBuffer complete = record;
        record = null;
        try (complete; JsonParser input = complete.asParser(mapper)) {
            return reader.readValue(input);
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
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
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
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
        mapper = null;
        record = null;
        try (owned) {
            if (pending != null) {
                pending.close();
            }
        } catch (IOException | RuntimeException error) {
            throw Jackson2Support.failure(error);
        }
    }
}
