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
    void plainConfigurationLoadsWithoutAnyBootArtifactOnTheClasspath() throws Exception {
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path").split(
                        Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("spring-boot"))
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

    public static void main(String[] args) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.springframework.boot.autoconfigure.AutoConfiguration"));
        try (var context = SpringContexts.context(true)) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(JsonFacadeConfiguration.class);
            context.refresh();
            assertSame(context.getBean(JsonMapper.class), Json.global());
            assertEquals(3, Json.global().read("3", Integer.class));
        }
    }
}
