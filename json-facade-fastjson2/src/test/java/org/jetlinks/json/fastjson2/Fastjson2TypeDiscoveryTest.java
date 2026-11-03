package org.jetlinks.json.fastjson2;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.annotation.JSONType;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 原生危险开关虽已过时，仍需覆盖显式关闭、手动重开和 JVM 全局继承。
@SuppressWarnings("deprecation")
class Fastjson2TypeDiscoveryTest {
    private static JsonMapper mapper() {
        return Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions()).build();
    }

    private static String typed() {
        return "{\"@type\":\"" + AutoItem.class.getName() + "\",\"value\":7}";
    }

    @Test
    void defaultsDisableClassNameDiscoveryAcrossInputsAndKeepDeclaredSubtypes() {
        try (var warnings = new Warnings()) {
            JsonMapper json = mapper();
            String input = typed();
            byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            assertInstanceOf(Map.class, json.read(input, Object.class));
            assertInstanceOf(Map.class, json.read(bytes, Object.class));
            assertInstanceOf(Map.class, json.read(new ByteArrayInputStream(bytes), Object.class));
            assertInstanceOf(Map.class, json.read(new StringReader(input), Object.class));
            assertInstanceOf(Map.class, json.read("{\"payload\":" + input + "}", Holder.class).payload());
            assertThrows(JsonException.class, () -> json.read("\"java.lang.String\"", Class.class));
            assertInstanceOf(NamedChild.class, json.read("{\"kind\":\"known\",\"value\":7}", NamedBase.class));
            assertTrue(warnings.records.isEmpty());
        }
    }

    @Test
    void explicitNativeAutoTypeOptInWarnsOnceEvenForPreparedCompactOutput() {
        JsonMapper base = mapper();
        try (var warnings = new Warnings()) {
            JsonMapper enabled = Json.builder().provider("fastjson2")
                    .options(Fastjson2Provider.nativeOptions().withPretty(true))
                    .configureNative(Fastjson2Builder.class, builder -> {
                        builder.reader().getProvider().setDisableAutoType(false);
                        builder.reader().config(JSONReader.Feature.SupportAutoType);
                    }).build();
            assertEquals(1, warnings.records.size());
            for (int i = 0; i < 3; i++) {
                assertEquals(7, assertInstanceOf(AutoItem.class, enabled.read(typed(), Object.class)).value);
            }
            assertEquals(1, warnings.records.size());
            assertInstanceOf(Map.class, base.read(typed(), Object.class));
            JsonMapper prepared = enabled.withOptions(JsonOperationOptions.builder().pretty(false).build());
            assertEquals(2, warnings.records.size());
            assertInstanceOf(AutoItem.class, prepared.read(typed(), Object.class));
            assertEquals(2, warnings.records.size());
            assertFalse(warnings.records.get(0).getMessage().contains(AutoItem.class.getName()));
        }
    }

    @Test
    void allNativeTypeEnablingPathsAreDiagnosedWithoutPerValueWarnings() {
        JsonMapper base = mapper();
        List<Consumer<JSONReader.Context>> optIns = List.of(
                context -> context.getProvider().setDisableAutoType(false),
                context -> context.config(JSONReader.Feature.SupportAutoType),
                context -> context.config(JSONReader.Feature.SupportClassForName),
                context -> context.config(JSONReader.autoTypeFilter(AutoItem.class)),
                context -> context.getProvider().setAutoTypeBeforeHandler(JSONReader.autoTypeFilter(AutoItem.class)));
        try (var warnings = new Warnings()) {
            for (Consumer<JSONReader.Context> optIn : optIns) {
                int before = warnings.records.size();
                JsonMapper operation = base.withOptions(JsonOperationOptions.builder()
                        .nativeReader(JSONReader.Context.class, context -> {
                            optIn.accept(context);
                            return context;
                        }).build());
                assertEquals(before + 1, warnings.records.size());
                assertEquals(1, operation.read("1", Integer.class));
                assertEquals(before + 1, warnings.records.size());
            }
        }
    }

    @Test
    void inheritedJvmFeaturesAndHandlersAreDisabledWithoutMutatingGlobalDefaults() throws Exception {
        Path log = Files.createTempFile("fastjson2-type-defaults-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfastjson2.autoTypeBeforeHandler=" + GlobalHandler.class.getName(),
                "-Dfastjson2.autoTypeAccept=" + AutoItem.class.getName(),
                "-cp", System.getProperty("surefire.test.class.path"), DefaultsProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Type defaults child JVM timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    public static class DefaultsProbe {
        public static void main(String[] args) {
            // 原生禁止全局开启 SupportAutoType；可继承的类名加载开关和处理器仍须隔离。
            JSON.config(JSONReader.Feature.SupportClassForName);
            if (new ObjectReaderProvider().getAutoTypeBeforeHandler() == null) {
                throw new AssertionError("Global before-handler fixture was not installed");
            }
            var nativeProvider = new ObjectReaderProvider();
            nativeProvider.setDisableAutoType(true);
            nativeProvider.setAutoTypeBeforeHandler(null);
            assertSame(AutoItem.class, nativeProvider.checkAutoType(AutoItem.class.getName(), null, 0));
            JsonMapper json = Json.builder().provider("fastjson2").options(Fastjson2Provider.nativeOptions())
                    .configureNative(Fastjson2Builder.class, builder -> {
                        assertTrue(builder.reader().getProvider().isDisableAutoType());
                        assertNull(builder.reader().getProvider().getAutoTypeBeforeHandler());
                        assertFalse(builder.reader().isEnabled(JSONReader.Feature.SupportAutoType));
                        assertFalse(builder.reader().isEnabled(JSONReader.Feature.SupportClassForName));
                    }).build();
            assertInstanceOf(Map.class, json.read(typed(), Object.class));
            assertThrows(JsonException.class, () -> json.read("\"java.lang.String\"", Class.class));
            assertFalse(JSON.isEnabled(JSONReader.Feature.SupportAutoType));
            assertTrue(JSON.isEnabled(JSONReader.Feature.SupportClassForName));
            assertInstanceOf(GlobalHandler.class, new ObjectReaderProvider().getAutoTypeBeforeHandler());
        }
    }

    public static class GlobalHandler implements JSONReader.AutoTypeBeforeHandler {
        @Override
        public Class<?> apply(String typeName, Class<?> expectClass, long features) {
            return typeName.equals(AutoItem.class.getName()) ? AutoItem.class : null;
        }
    }

    public static class AutoItem { public int value; }
    public record Holder(Object payload) { }

    @JSONType(seeAlso = NamedChild.class, typeKey = "kind")
    public static class NamedBase { public int value; }
    @JSONType(typeName = "known")
    public static class NamedChild extends NamedBase { }

    private static final class Warnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(Fastjson2Provider.class.getName());
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
