package org.jetlinks.json.jackson3;

import java.nio.ByteBuffer;
import tools.jackson.core.JsonParser;
import tools.jackson.core.async.ByteBufferFeeder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.util.TokenBuffer;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonReadOptions;
import org.jetlinks.json.JsonToken;
import org.jetlinks.json.JsonType;
import org.jetlinks.json.internal.AbstractReadSession;

/** Jackson async tokenizer and record-local native binding; shared code owns framing and selection. */
final class Jackson3Session<T> extends AbstractReadSession<T> {
    private final JsonParser parser;
    private final ByteBufferFeeder feeder;
    private final ObjectReader reader;
    private TokenBuffer record;
    private boolean exhausted;

    Jackson3Session(JsonMapper mapper, ObjectMapper nativeMapper, JsonType<T> type,
                    JsonReadOptions options) throws tools.jackson.core.JacksonException {
        super(options, mapper.options().maxValueBytes());
        reader = nativeMapper.readerFor(nativeMapper.constructType(type.type()));
        parser = nativeMapper.createNonBlockingByteBufferParser();
        feeder = (ByteBufferFeeder) parser.nonBlockingInputFeeder();
    }

    @Override
    protected Event nextEvent() {
        return Jackson3Support.call(() -> {
            tools.jackson.core.JsonToken nativeToken = parser.nextToken();
            exhausted = nativeToken == null;
            JsonToken token = Jackson3Reader.token(nativeToken);
            String name = token == JsonToken.FIELD_NAME && tracksPath() ? parser.getString() : null;
            long line = parser.currentTokenLocation().getLineNr();
            return new Event(token, name, parser.currentTokenLocation().getByteOffset(),
                    parser.currentLocation().getByteOffset(), line, parser.currentLocation().getLineNr());
        });
    }

    @Override
    protected void startRecord() {
        record = TokenBuffer.forBuffering(parser, parser.objectReadContext());
    }

    @Override
    protected void appendToken() {
        Jackson3Support.call(() -> {
            if (parser.currentToken().isNumeric()) {
                // Delay number conversion until binding, retaining text for String/custom readers.
                record.writeNumber(parser.getString(),
                        parser.currentToken() == tools.jackson.core.JsonToken.VALUE_NUMBER_INT);
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
        return Jackson3Support.call(() -> {
            try (complete) {
                return reader.readValue(complete);
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
