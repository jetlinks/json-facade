package org.jetlinks.json.jackson3;

import java.nio.ByteBuffer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.async.ByteBufferFeeder;
import tools.jackson.databind.ObjectMapper;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.AbstractReadSession;

/** Jackson async tokenizer. Binding/framing/selection remain in the shared JDK state machine. */
final class Jackson3Session<T> extends AbstractReadSession<T> {
    private final JsonParser parser;
    private final ByteBufferFeeder feeder;
    private boolean exhausted;

    Jackson3Session(JsonMapper mapper, ObjectMapper nativeMapper, JsonType<T> type,
                    JsonReadOptions options) throws tools.jackson.core.JacksonException {
        super(mapper, type, options);
        parser = nativeMapper.createNonBlockingByteBufferParser();
        feeder = (ByteBufferFeeder) parser.nonBlockingInputFeeder();
    }

    @Override
    protected Event nextEvent() {
        return Jackson3Support.call(() -> {
            tools.jackson.core.JsonToken nativeToken = parser.nextToken();
            exhausted = nativeToken == null;
            JsonToken token = Jackson3Reader.token(nativeToken);
            Object value = null;
            if (token != null) {
                value = switch (token) {
                    case FIELD_NAME, STRING -> parser.getString();
                    case NUMBER -> nativeToken == tools.jackson.core.JsonToken.VALUE_NUMBER_INT
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
        Jackson3Support.call(() -> {
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
        Jackson3Support.call(() -> {
            parser.close();
            return null;
        });
    }
}
