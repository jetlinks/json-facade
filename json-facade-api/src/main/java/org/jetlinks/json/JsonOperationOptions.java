package org.jetlinks.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 不可变的操作覆盖，未指定项继承映射器；通过 {@link JsonMapper#withOptions} 准备后复用。
 * 通用绑定开关、业务属性及原生读写器定制相互独立，不管理分帧、Pointer 或解析预算。
 * <p>原生回调仅在准备时执行；派生新的操作映射器时可重放，须无外部副作用。
 * 回调不得保留或修改共享原生对象，须返回不可变读写器的配置结果。
 *
 * @since 1.0.0
 */
public final class JsonOperationOptions {
    /** 无覆盖；withOptions 返回原映射器。 */
    public static final JsonOperationOptions EMPTY = new Builder().build();

    private final Boolean losslessNumbers;
    private final Boolean failUnknownProperties;
    private final Boolean failDuplicateKeys;
    private final Boolean omitNulls;
    private final Boolean pretty;
    private final Map<JsonAttribute<?>, Object> attributes;
    private final List<NativeOption<?>> readers;
    private final List<NativeOption<?>> writers;

    private JsonOperationOptions(Builder builder) {
        losslessNumbers = builder.losslessNumbers;
        failUnknownProperties = builder.failUnknownProperties;
        failDuplicateKeys = builder.failDuplicateKeys;
        omitNulls = builder.omitNulls;
        pretty = builder.pretty;
        attributes = Map.copyOf(builder.attributes);
        readers = List.copyOf(builder.readers);
        writers = List.copyOf(builder.writers);
    }

    /** 创建操作本地构建器；构建器不支持并发。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 无类型浮点数是否保留精度；null 表示继承。 */
    public Boolean losslessNumbers() {
        return losslessNumbers;
    }

    /** 绑定对象时是否拒绝未知属性；null 表示继承。 */
    public Boolean failUnknownProperties() {
        return failUnknownProperties;
    }

    /** 解析时是否拒绝重复字段；null 表示继承。 */
    public Boolean failDuplicateKeys() {
        return failDuplicateKeys;
    }

    /** 是否按原生全局包含策略省略 null；仍尊重更具体注解；null 表示继承。 */
    public Boolean omitNulls() {
        return omitNulls;
    }

    /** 同步输出是否格式化；流式输出始终紧凑；null 表示继承。 */
    public Boolean pretty() {
        return pretty;
    }

    /** 返回只读属性快照；属性值本身不进行深复制。 */
    public Map<JsonAttribute<?>, Object> attributes() {
        return attributes;
    }

    /** 是否完全没有覆盖，包括业务属性和原生回调。 */
    public boolean isEmpty() {
        return losslessNumbers == null && failUnknownProperties == null && failDuplicateKeys == null
                && omitNulls == null && pretty == null
                && attributes.isEmpty() && readers.isEmpty() && writers.isEmpty();
    }

    /**
     * 合并覆盖；后者覆盖已指定的通用项及同键属性，原生回调按先后顺序累积。
     * Provider 派生时使用；合并后的显式通用项仍约束全部原生回调。
     */
    public JsonOperationOptions overlay(JsonOperationOptions overrides) {
        Objects.requireNonNull(overrides, "overrides");
        if (overrides.isEmpty()) {
            return this;
        }
        Builder builder = new Builder();
        builder.losslessNumbers = overrides.losslessNumbers == null ? losslessNumbers : overrides.losslessNumbers;
        builder.failUnknownProperties = overrides.failUnknownProperties == null
                ? failUnknownProperties : overrides.failUnknownProperties;
        builder.failDuplicateKeys = overrides.failDuplicateKeys == null ? failDuplicateKeys : overrides.failDuplicateKeys;
        builder.omitNulls = overrides.omitNulls == null ? omitNulls : overrides.omitNulls;
        builder.pretty = overrides.pretty == null ? pretty : overrides.pretty;
        builder.attributes.putAll(attributes);
        builder.attributes.putAll(overrides.attributes);
        builder.readers.addAll(readers);
        builder.readers.addAll(overrides.readers);
        builder.writers.addAll(writers);
        builder.writers.addAll(overrides.writers);
        return builder.build();
    }

    /** Provider 在执行任何回调前校验整批原生类型，失败归为 CONFIGURATION。 */
    public void validateNativeTypes(Object reader, Object writer) {
        validateTypes(readers, reader);
        validateTypes(writers, writer);
    }

    /** Provider 在准备阶段应用读取回调，必须先校验全部读取和写入类型。 */
    public <T> T configureReader(Class<T> type, T reader) {
        return configure(readers, type, reader);
    }

    /** Provider 在准备阶段应用写入回调，必须先校验全部读取和写入类型。 */
    public <T> T configureWriter(Class<T> type, T writer) {
        return configure(writers, type, writer);
    }

    private static void validateTypes(List<NativeOption<?>> options, Object target) {
        Objects.requireNonNull(target, "target");
        for (NativeOption<?> option : options) {
            if (!option.type().isInstance(target)) {
                throw new JsonException(JsonException.Kind.CONFIGURATION,
                        "Native JSON operation requires " + option.type().getName()
                                + "; selected Provider supplies " + target.getClass().getName());
            }
        }
    }

    private static <T> T configure(List<NativeOption<?>> options, Class<T> type, T target) {
        Object configured = target;
        try {
            for (NativeOption<?> option : options) {
                configured = option.apply(configured);
            }
            // 回调只能派生同一原生读写器家族，不允许返回其他类型或 null。
            return type.cast(configured);
        } catch (RuntimeException error) {
            throw new JsonException(JsonException.Kind.CONFIGURATION,
                    "Native JSON operation callback failed", error);
        }
    }

    private record NativeOption<T>(Class<T> type, UnaryOperator<T> action) {
        Object apply(Object target) {
            return Objects.requireNonNull(action.apply(type.cast(target)), "native callback result");
        }
    }

    /** 操作覆盖构建器；build 防御性复制容器，回调和属性值仍由调用方保证线程安全。 */
    public static final class Builder {
        private Boolean losslessNumbers;
        private Boolean failUnknownProperties;
        private Boolean failDuplicateKeys;
        private Boolean omitNulls;
        private Boolean pretty;
        private final Map<JsonAttribute<?>, Object> attributes = new HashMap<>();
        private final List<NativeOption<?>> readers = new ArrayList<>();
        private final List<NativeOption<?>> writers = new ArrayList<>();

        private Builder() {
        }

        /** 设置无类型浮点数是否保留精度。 */
        public Builder losslessNumbers(boolean value) {
            losslessNumbers = value;
            return this;
        }

        /** 设置绑定对象时是否拒绝未知属性。 */
        public Builder failUnknownProperties(boolean value) {
            failUnknownProperties = value;
            return this;
        }

        /** 设置解析时是否拒绝重复字段。 */
        public Builder failDuplicateKeys(boolean value) {
            failDuplicateKeys = value;
            return this;
        }

        /** 设置是否按原生全局包含策略省略 null；仍尊重更具体注解。 */
        public Builder omitNulls(boolean value) {
            omitNulls = value;
            return this;
        }

        /** 设置同步输出是否格式化；流式输出始终紧凑。 */
        public Builder pretty(boolean value) {
            pretty = value;
            return this;
        }

        /** 设置非空业务属性；同键后值覆盖前值，值须可安全共享。 */
        public <T> Builder attribute(JsonAttribute<T> key, T value) {
            attributes.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(key.cast(value), "value"));
            return this;
        }

        /** 追加原生读取定制，如 ObjectReader.with(feature)，必须返回派生结果。 */
        public <T> Builder nativeReader(Class<T> type, UnaryOperator<T> action) {
            readers.add(new NativeOption<>(Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(action, "action")));
            return this;
        }

        /** 追加原生写入定制，如 ObjectWriter.with(feature)，必须返回派生结果。 */
        public <T> Builder nativeWriter(Class<T> type, UnaryOperator<T> action) {
            writers.add(new NativeOption<>(Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(action, "action")));
            return this;
        }

        /** 创建可共享快照；后续修改构建器不影响结果。 */
        public JsonOperationOptions build() {
            return new JsonOperationOptions(this);
        }
    }
}
