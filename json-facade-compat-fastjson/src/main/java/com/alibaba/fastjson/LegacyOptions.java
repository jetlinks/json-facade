package com.alibaba.fastjson;

import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.util.Objects;
import org.jetlinks.json.Json;
import org.jetlinks.json.JsonMapper;
import org.jetlinks.json.JsonOperationOptions;

/** 只翻译具有中立等价语义的显式选项；先校验整批，再发现或派生映射器。 */
@Deprecated(since = "1.0", forRemoval = false)
final class LegacyOptions {
    private static final int READ = Feature.UseBigDecimal.mask | Feature.IgnoreNotMatch.mask;
    private static final int WRITE = SerializerFeature.PrettyFormat.mask | SerializerFeature.WriteMapNullValue.mask;

    private LegacyOptions() { }

    static JsonMapper reader(Feature... features) {
        return reader(Feature.of(Objects.requireNonNull(features, "features")));
    }

    static JsonMapper reader(int mask) {
        validateReader(mask);
        JsonMapper mapper = Json.global();
        boolean lossless = (mask & Feature.UseBigDecimal.mask) != 0 && !mapper.options().losslessNumbers();
        boolean ignoreUnknown = (mask & Feature.IgnoreNotMatch.mask) != 0 && mapper.options().failUnknownProperties();
        if (!lossless && !ignoreUnknown) {
            return mapper;
        }
        JsonOperationOptions.Builder options = JsonOperationOptions.builder();
        if (lossless) {
            options.losslessNumbers(true);
        }
        if (ignoreUnknown) {
            options.failUnknownProperties(false);
        }
        return mapper.withOptions(options.build());
    }

    static JsonMapper writer(SerializerFeature... features) {
        int mask = SerializerFeature.of(Objects.requireNonNull(features, "features"));
        validateWriter(mask);
        return writer(Json.global(), mask);
    }

    static JsonMapper writer(int mask, SerializerFeature... extra) {
        int combined = mask | SerializerFeature.of(Objects.requireNonNull(extra, "features"));
        validateWriter(combined);
        return writer(Json.global(), combined);
    }

    static JsonMapper writer(JsonMapper mapper, SerializerFeature... features) {
        int mask = SerializerFeature.of(Objects.requireNonNull(features, "features"));
        validateWriter(mask);
        return writer(mapper, mask);
    }

    private static JsonMapper writer(JsonMapper mapper, int mask) {
        boolean pretty = (mask & SerializerFeature.PrettyFormat.mask) != 0 && !mapper.options().pretty();
        boolean keepNull = (mask & SerializerFeature.WriteMapNullValue.mask) != 0 && mapper.options().omitNulls();
        if (!pretty && !keepNull) {
            return mapper;
        }
        JsonOperationOptions.Builder options = JsonOperationOptions.builder();
        if (pretty) {
            options.pretty(true);
        }
        if (keepNull) {
            options.omitNulls(false);
        }
        return mapper.withOptions(options.build());
    }

    static JsonMapper pretty(boolean enabled) {
        JsonMapper mapper = Json.global();
        return mapper.options().pretty() == enabled ? mapper
                : mapper.withOptions(JsonOperationOptions.builder().pretty(enabled).build());
    }

    private static void validateReader(int mask) {
        if ((mask & ~READ) == 0) {
            return;
        }
        for (Feature feature : Feature.values()) {
            if ((mask & ~READ & feature.mask) != 0) {
                throw JSON.unsupported("Feature." + feature, "JsonOperationOptions / Provider configuration");
            }
        }
        throw JSON.unsupported("Feature mask 0x" + Integer.toHexString(mask), "JsonOperationOptions");
    }

    private static void validateWriter(int mask) {
        if ((mask & ~WRITE) == 0) {
            return;
        }
        for (SerializerFeature feature : SerializerFeature.values()) {
            if ((mask & ~WRITE & feature.mask) != 0) {
                throw JSON.unsupported("SerializerFeature." + feature, "JsonOperationOptions / Provider configuration");
            }
        }
        throw JSON.unsupported("SerializerFeature mask 0x" + Integer.toHexString(mask), "JsonOperationOptions");
    }
}
