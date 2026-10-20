package org.jetlinks.json.spring;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainSpringClasspathTest {
    @Test
    void plainConfigurationLoadsWithoutBootReactorOrNettyOnTheClasspath() throws Exception {
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path").split(
                        Pattern.quote(File.pathSeparator)))
                .filter(PlainSpringClasspathTest::plainDependency)
                .collect(Collectors.joining(File.pathSeparator));
        Path log = Files.createTempFile("plain-spring-", ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, PlainSpringClasspathTest.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Plain Spring child JVM timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    private static boolean plainDependency(String entry) {
        String name = Path.of(entry).getFileName().toString();
        return !name.startsWith("spring-boot") && !name.startsWith("reactor-")
                && !name.startsWith("reactive-streams") && !name.startsWith("netty-")
                && !entry.contains("json-facade-reactor") && !entry.contains("json-facade-netty");
    }

    public static void main(String[] args) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.autoconfigure.AutoConfiguration"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("reactor.core.publisher.Flux"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.netty.buffer.ByteBuf"));
        try (var context = SpringContexts.context(true)) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(JsonFacadeConfiguration.class);
            context.refresh();
            assertSame(context.getBean(JsonMapper.class), Json.global());
            assertEquals(3, Json.global().read("3", Integer.class));
        }
    }
}
