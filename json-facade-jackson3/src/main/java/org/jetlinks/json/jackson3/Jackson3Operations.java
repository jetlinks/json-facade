package org.jetlinks.json.jackson3;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.filter.FilteringParserDelegate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import org.jetlinks.json.JsonException;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;
import org.jetlinks.json.JsonOptions;
import org.jetlinks.json.internal.JsonTreeBinding;

/** 在准备阶段派生原生配置；不在逐值绑定路径执行回调或隔离缓存。 */
final class Jackson3Operations {
    private Jackson3Operations() {
    }

    static JsonMapper prepare(ObjectMapper source, JsonOptions defaults, Jackson3Mapper base,
                              JsonOperationOptions overrides) {
        try {
            ObjectMapper mapper = source;
            // null 省略固化在 serializer 中，通过原生复制隔离配置和缓存。
            if (overrides.omitNulls() != null) {
                mapper = source.rebuild().changeDefaultPropertyInclusion(
                        previous -> inclusion(overrides.omitNulls())).build();
            }
            ObjectReader reader = readOverrides(mapper.reader(), overrides);
            ObjectWriter writer = writeOverrides(mapper.writer(), overrides);
            overrides.validateNativeTypes(reader, writer);
            reader = overrides.configureReader(ObjectReader.class, reader);
            writer = overrides.configureWriter(ObjectWriter.class, writer);
            validateResources(mapper, reader, writer);
            boolean pretty = validateStreams(mapper, reader, writer);
            JsonOptions effective = new JsonOptions(
                    reader.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                    reader.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
                    reader.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION),
                    overrides.omitNulls() == null ? defaults.omitNulls() : overrides.omitNulls(),
                    pretty,
                    defaults.maxDepth(), defaults.maxStringLength(), defaults.maxNumberLength(),
                    defaults.maxValueBytes());
            validateOverrides(overrides, effective);
            Jackson3Provider.warnTypeDiscovery(reader.getConfig());
            JsonTreeBinding binding = new JsonTreeBinding();
            reader = reader.withAttribute(JsonTreeBinding.class, binding);
            return binding.initialize(new Jackson3Mapper(mapper, effective, base, overrides, reader, writer));
        } catch (JsonException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "Cannot prepare JSON operation", error);
        }
    }

    private static ObjectReader readOverrides(ObjectReader reader, JsonOperationOptions options) {
        if (options.losslessNumbers() != null) {
            reader = options.losslessNumbers() ? reader.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    : reader.without(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        }
        if (options.failUnknownProperties() != null) {
            reader = options.failUnknownProperties() ? reader.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    : reader.without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        }
        if (options.failDuplicateKeys() != null) {
            reader = options.failDuplicateKeys() ? reader.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    : reader.without(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        }
        for (var attribute : options.attributes().entrySet()) {
            reader = reader.withAttribute(attribute.getKey(), attribute.getValue());
        }
        return reader;
    }

    private static ObjectWriter writeOverrides(ObjectWriter writer, JsonOperationOptions options) {
        if (options.pretty() != null) {
            writer = options.pretty() ? writer.with(SerializationFeature.INDENT_OUTPUT)
                    : writer.without(SerializationFeature.INDENT_OUTPUT);
        }
        for (var attribute : options.attributes().entrySet()) {
            writer = writer.withAttribute(attribute.getKey(), attribute.getValue());
        }
        return writer;
    }

    private static JsonInclude.Value inclusion(boolean omit) {
        var value = omit ? JsonInclude.Include.NON_NULL : JsonInclude.Include.ALWAYS;
        return JsonInclude.Value.construct(value, value);
    }

    private static void validateOverrides(JsonOperationOptions overrides, JsonOptions effective) {
        require(overrides.losslessNumbers() == null || overrides.losslessNumbers() == effective.losslessNumbers(),
                "JsonOperationOptions.losslessNumbers");
        require(overrides.failUnknownProperties() == null || overrides.failUnknownProperties() == effective.failUnknownProperties(),
                "JsonOperationOptions.failUnknownProperties");
        require(overrides.failDuplicateKeys() == null || overrides.failDuplicateKeys() == effective.failDuplicateKeys(),
                "JsonOperationOptions.failDuplicateKeys");
        require(overrides.pretty() == null || overrides.pretty() == effective.pretty(),
                "JsonOperationOptions.pretty");
    }

    private static void validateResources(ObjectMapper mapper, ObjectReader reader, ObjectWriter writer) {
        // reader 共享按类型缓存的反序列化器；更换多态策略须在构建期使用独立映射器。
        var policy = mapper.deserializationConfig();
        require(reader.getConfig().getDefaultTyper(null) == policy.getDefaultTyper(null)
                && reader.getConfig().getPolymorphicTypeValidator() == policy.getPolymorphicTypeValidator(),
                "type-discovery policy (use configureNative at build time)");
        require(reader.parserFactory() == mapper.tokenStreamFactory()
                && writer.generatorFactory() == mapper.tokenStreamFactory(), "original stream factory");
        require(reader.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS), "single-root reads");
        require(!reader.isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE), "caller-owned input");
        require(!reader.isEnabled(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION), "input source privacy");
        require(!writer.isEnabled(StreamWriteFeature.AUTO_CLOSE_TARGET), "caller-owned output");
        require(!writer.isEnabled(StreamWriteFeature.AUTO_CLOSE_CONTENT), "incomplete-document handling");
        require(!writer.isEnabled(SerializationFeature.CLOSE_CLOSEABLE), "caller-owned values");
        require(reader.getValueType() == null, "operation target supplied by facade");
    }

    private static void require(boolean compatible, String policy) {
        if (!compatible) {
            throw new JsonException(JsonException.Kind.CONFIGURATION,
                    "Native JSON operation conflicts with " + policy);
        }
    }

    private static boolean validateStreams(ObjectMapper mapper, ObjectReader reader, ObjectWriter writer) {
        // 空输入只初始化原生迭代器，不绑定业务值；过滤游标会掩盖原始 EOF，须在准备阶段拒绝。
        try (var values = reader.forType(Object.class).readValues("");
             var generator = writer.createGenerator(java.io.OutputStream.nullOutputStream())) {
            var parser = values.parser();
            require(!(parser instanceof FilteringParserDelegate), "unfiltered single-root reads");
            require(!generator.isEnabled(StreamWriteFeature.AUTO_CLOSE_TARGET), "caller-owned output");
            require(!generator.isEnabled(StreamWriteFeature.AUTO_CLOSE_CONTENT), "incomplete-document handling");
            var actual = parser.streamReadConstraints();
            var expected = mapper.tokenStreamFactory().streamReadConstraints();
            require(actual.getMaxNestingDepth() <= expected.getMaxNestingDepth()
                    && actual.getMaxStringLength() <= expected.getMaxStringLength()
                    && actual.getMaxNumberLength() <= expected.getMaxNumberLength(), "native read limits");
            require(generator.streamWriteConstraints().getMaxNestingDepth()
                    <= mapper.tokenStreamFactory().streamWriteConstraints().getMaxNestingDepth(),
                    "native write limits");
            return generator.getPrettyPrinter() != null;
        } catch (RuntimeException error) {
            throw new JsonException(JsonException.Kind.CONFIGURATION, "Cannot validate JSON operation streams", error);
        }
    }
}
