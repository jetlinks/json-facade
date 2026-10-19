package org.jetlinks.json.jackson2;

import java.io.IOException;
import java.nio.ByteBuffer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.async.ByteBufferFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.AbstractReadSession;

/** Jackson async tokenizer. Binding/framing/selection remain in the shared JDK state machine. */
final class Jackson2Session<T> extends AbstractReadSession<T> {
    private final JsonParser parser;
    private final ByteBufferFeeder feeder;
    private boolean exhausted;

    Jackson2Session(JsonMapper mapper, ObjectMapper nativeMapper, JsonType<T> type,
                    JsonReadOptions options) throws IOException {
        super(mapper, type, options);
        parser = nativeMapper.getFactory().createNonBlockingByteBufferParser();
        feeder = (ByteBufferFeeder) parser.getNonBlockingInputFeeder();
    }

    @Override
    protected Event nextEvent() {
        return Jackson2Support.call(() -> {
            com.fasterxml.jackson.core.JsonToken nativeToken = parser.nextToken();
            exhausted = nativeToken == null;
            JsonToken token = Jackson2Reader.token(nativeToken);
            Object value = null;
            if (token != null) {
                value = switch (token) {
                    case FIELD_NAME, STRING -> parser.getText();
                    case NUMBER -> nativeToken == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT
                            ? parser.getNumberValue() : parser.getDecimalValue();
                    case BOOLEAN -> parser.getBooleanValue();
                    default -> null;
                };
            }
            long line = parser.currentTokenLocation().getLineNr();
            return new Event(token, value, parser.currentTokenLocation().getByteOffset(),
                    parser.currentLocation().getByteOffset(), line, parser.currentLocation().getLineNr());
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
            parser.close();
            return null;
        });
    }
}
