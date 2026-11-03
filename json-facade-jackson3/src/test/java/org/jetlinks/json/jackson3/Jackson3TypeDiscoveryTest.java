package org.jetlinks.json.jackson3;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonReadOptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Jackson3TypeDiscoveryTest {
    private static final AtomicInteger CLASS_INITIALIZATIONS = new AtomicInteger();
    private static JsonMapper mapper() {
        return Json.builder().provider(new Jackson3Provider()).build();
    }

    private static String typed(Class<?> type) {
        return "{\"@type\":\"" + type.getName() + "\",\"value\":7}";
    }

    @Test
    void defaultsKeepClassNamesInertAndAllowDeclaredLogicalSubtypes() {
        try (var warnings = new Warnings()) {
            JsonMapper json = mapper();
            for (String key : List.of("@type", "@class")) {
                assertInstanceOf(Map.class, json.read(typed(AutoItem.class).replace("@type", key), Object.class));
            }
            assertInstanceOf(List.class, json.read("[\"" + AutoItem.class.getName() + "\",{\"value\":7}]", Object.class));
            assertThrows(JsonException.class, () -> json.read(typed(ClassChild.class), ClassBase.class));
            assertThrows(JsonException.class, () -> json.read(typed(NeverInitialized.class), ClassBase.class));
            assertEquals(0, CLASS_INITIALIZATIONS.get());
            String minimal = typed(MinimalChild.class).replace(MinimalChild.class.getName(),
                    "." + MinimalChild.class.getName().substring(MinimalChild.class.getPackageName().length() + 1));
            assertThrows(JsonException.class, () -> json.read(minimal, MinimalBase.class));
            assertInstanceOf(NamedChild.class, json.read("{\"kind\":\"known\",\"value\":7}", NamedBase.class));
            assertTrue(warnings.records.isEmpty());
        }
    }

    @Test
    void nativeClassNameValidatorOptInWarnsDuringPreparationOnly() {
        JsonMapper base = mapper();
        try (var warnings = new Warnings()) {
            JsonMapper enabled = Json.builder().provider(new Jackson3Provider())
                    .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, builder ->
                            builder.polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder()
                                    .allowIfSubType(ClassChild.class).build())).build();
            assertEquals(1, warnings.records.size());
            for (int i = 0; i < 3; i++) {
                assertInstanceOf(ClassChild.class, enabled.read(typed(ClassChild.class), ClassBase.class));
            }
            assertEquals(1, warnings.records.size());
            JsonMapper prepared = enabled.withOptions(JsonOperationOptions.builder().pretty(true).build());
            assertEquals(2, warnings.records.size());
            assertInstanceOf(ClassChild.class, prepared.read(typed(ClassChild.class), ClassBase.class));
            assertEquals(2, warnings.records.size());
            assertThrows(JsonException.class, () -> base.read(typed(ClassChild.class), ClassBase.class));
        }
    }

    @Test
    void nativeDefaultTypingWarnsAndReaderPolicyReplacementCannotPolluteTheBase() {
        var validator = BasicPolymorphicTypeValidator.builder().allowIfSubType(AutoItem.class).build();
        try (var warnings = new Warnings()) {
            JsonMapper enabled = Json.builder().provider(new Jackson3Provider())
                    .configureNative(tools.jackson.databind.json.JsonMapper.Builder.class, builder ->
                            builder.activateDefaultTypingAsProperty(validator,
                                    tools.jackson.databind.DefaultTyping.JAVA_LANG_OBJECT, "@type")).build();
            assertEquals(1, warnings.records.size());
            assertEquals(7, assertInstanceOf(AutoItem.class, enabled.read(typed(AutoItem.class), Object.class)).value);
            var nativeMapper = tools.jackson.databind.json.JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .activateDefaultTypingAsProperty(validator,
                            tools.jackson.databind.DefaultTyping.JAVA_LANG_OBJECT, "@type").build();
            JsonMapper base = mapper();
            assertEquals(JsonException.Kind.CONFIGURATION, assertThrows(JsonException.class, () ->
                    base.withOptions(JsonOperationOptions.builder().nativeReader(ObjectReader.class,
                            reader -> reader.with(nativeMapper.deserializationConfig())).build())).kind());
            assertInstanceOf(Map.class, base.read(typed(AutoItem.class), Object.class));
            assertEquals(1, warnings.records.size());
        }
    }

    @Test
    void classNameDenialPropagatesToIteratorsAndIncrementalBinding() {
        JsonMapper json = mapper().withOptions(JsonOperationOptions.builder().pretty(true).build());
        byte[] value = typed(ClassChild.class).getBytes(StandardCharsets.UTF_8);
        assertThrows(JsonException.class, () -> {
            try (var iterator = json.iterateArray(new ByteArrayInputStream(
                    ("[" + typed(ClassChild.class) + "]").getBytes(StandardCharsets.UTF_8)), ClassBase.class)) {
                iterator.next();
            }
        });
        try (var session = json.readSession(ClassBase.class, JsonReadOptions.of(JsonReadOptions.Mode.SINGLE_VALUE))) {
            session.feed(ByteBuffer.wrap(value));
            session.endInput();
            assertThrows(JsonException.class, session::read);
        }
    }

    public static class AutoItem { public int value; }

    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
    public static class ClassBase { public int value; }
    public static class ClassChild extends ClassBase { }
    public static class NeverInitialized extends ClassBase {
        static { CLASS_INITIALIZATIONS.incrementAndGet(); }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.MINIMAL_CLASS, property = "@type")
    public static class MinimalBase { public int value; }
    public static class MinimalChild extends MinimalBase { }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = NamedChild.class, name = "known"))
    public static class NamedBase { public int value; }
    public static class NamedChild extends NamedBase { }

    private static final class Warnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(Jackson3Provider.class.getName());
        private final List<LogRecord> records = new ArrayList<>();

        private Warnings() { logger.addHandler(this); }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { logger.removeHandler(this); }
    }
}
