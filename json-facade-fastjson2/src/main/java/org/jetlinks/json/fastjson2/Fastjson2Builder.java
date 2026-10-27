package org.jetlinks.json.fastjson2;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.reader.ObjectReaderProvider;
import com.alibaba.fastjson2.writer.ObjectWriterProvider;
import org.jetlinks.json.JsonOptions;

/**
 * 构建阶段借用的 fastjson2 原生配置，读写各使用独立 Provider，不修改 fastjson2 全局注册。
 * 例如通过 {@code builder.reader().getProvider().mixIn(Device.class, DeviceMixin.class)} 注册 Mixin。
 * 仅供 configureNative 回调使用，不支持并发，不得保留或在映射器发布后修改。
 * 准备操作变体会重放构建回调，以隔离原生缓存及树所属映射器；回调须无外部副作用。
 *
 * @since 1.0.0
 * @see Fastjson2Provider
 */
public final class Fastjson2Builder {
    private final JSONReader.Context reader;
    private final JSONWriter.Context writer;

    // SupportAutoType 已被原生标为过时；这里只显式关闭它，避免继承 JVM 全局开关。
    @SuppressWarnings("deprecation")
    Fastjson2Builder(JsonOptions options) {
        var provider = new ReaderProvider();
        provider.setDisableAutoType(true);
        provider.setAutoTypeBeforeHandler(null);
        reader = new JSONReader.Context(provider);
        reader.config(JSONReader.Feature.SupportAutoType, false);
        reader.config(JSONReader.Feature.SupportClassForName, false);
        reader.config(JSONReader.Feature.UseNativeObject);
        reader.config(JSONReader.Feature.UseDoubleForDecimals, !options.losslessNumbers());
        reader.config(JSONReader.Feature.ErrorOnUnknownProperties, options.failUnknownProperties());
        reader.setMaxLevel(options.maxDepth());
        writer = new JSONWriter.Context(new ObjectWriterProvider());
        writer.config(JSONWriter.Feature.WriteNulls, !options.omitNulls());
        writer.config(JSONWriter.Feature.PrettyFormat, options.pretty());
        writer.setMaxLevel(options.maxDepth());
    }

    /**
     * 借用原生读取配置，可注册 ObjectReader、Mixin 或设置日期格式；直接绑定中立树须配置 DisableReferenceDetect。
     * 默认关闭 AutoType/按名称加载 Class 并清除继承的 AutoType 处理器；显式重开时在准备阶段警告。
     */
    public JSONReader.Context reader() {
        return reader;
    }

    /** 借用原生写入配置，可注册 ObjectWriter、Mixin、命名策略或设置引用检测。 */
    public JSONWriter.Context writer() {
        return writer;
    }

    private static final class ReaderProvider extends ObjectReaderProvider {
        @Override
        public Class<?> checkAutoType(String typeName, Class<?> expectedClass, long features) {
            // 原生通用 Object 路径仍会检查 JVM 接受列表，在公开解析入口统一落实禁用开关。
            return isDisableAutoType() ? null : super.checkAutoType(typeName, expectedClass, features);
        }
    }
}
