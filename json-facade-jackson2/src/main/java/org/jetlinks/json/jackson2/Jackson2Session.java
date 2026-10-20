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
    private final JsonParser parser;
    private final ByteBufferFeeder feeder;
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private TokenBuffer record;
    private boolean exhausted;

    Jackson2Session(JsonMapper mapper, ObjectMapper nativeMapper, JsonType<T> type,
                    JsonReadOptions options) throws IOException {
        this(mapper, nativeMapper, type, options, false);
    }

    Jackson2Session(JsonMapper mapper, ObjectMapper nativeMapper, JsonType<T> type,
                    JsonReadOptions options, boolean events) throws IOException {
        super(options, mapper.options().maxValueBytes(), events);
        this.mapper = nativeMapper;
        reader = nativeMapper.readerFor(nativeMapper.constructType(type.type()));
        parser = nativeMapper.getFactory().createNonBlockingByteBufferParser();
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
        } catch (Exception error) {
            throw Jackson2Support.failure(error);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T bindEvent(JsonToken token) {
        return Jackson2Support.call(() -> {
            String text = switch (token) {
                case FIELD_NAME, STRING, NUMBER, BOOLEAN -> parser.getText();
                default -> null;
            };
            // Only the eventSession factory enables this branch, with T fixed to JsonEvent.
            return (T) JsonEvent.token(token, text);
        });
    }

    @Override
    protected void startRecord() {
        record = new TokenBuffer(parser);
    }

    @Override
    protected void appendToken() {
        Jackson2Support.call(() -> {
            if (parser.currentToken().isNumeric()) {
                // The public TokenBuffer API preserves both the numeric token kind and its lexeme.
                // Binding then chooses Double/BigDecimal/coercion using the reader's configuration.
                record.writeNumber(parser.getText(),
                        parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT);
            } else {
                record.copyCurrentEvent(parser);
            }
            return null;
        });
    }

    @Override
    protected T bindRecord() {
        TokenBuffer complete = record;
        record = null;
        return Jackson2Support.call(() -> {
            try (complete; JsonParser input = complete.asParser(mapper)) {
                return reader.readValue(input);
            }
        });
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
        Jackson2Support.call(() -> {
            feeder.feedInput(input);
            return null;
        });
    }

    @Override
    protected void finishParser() {
        feeder.endOfInput();
    }

    @Override
    protected void closeParser() {
        Jackson2Support.call(() -> {
            try (parser) {
                if (record != null) {
                    record.close();
                    record = null;
                }
            }
            return null;
        });
    }
}
