package org.jetlinks.json;

import java.util.Objects;

/**
 * 单次增量读取的分帧、定位、null 策略和单条记录预算。
 * Pointer 定位仅用于单文档，按令牌增量跳过无关内容；每个被绑定的结果仍须完整物化。
 * 实际字节预算取本选项与映射器预算的较小值。
 *
 * @param mode 根值的分帧方式
 * @param selection 可空的单文档定位；仅允许搭配 {@code SINGLE_VALUE}
 * @param nullPolicy 响应式适配器处理 Java null 的策略；直接使用会话时仍可读到 Java null
 * @param maxValueBytes 正整数，单个物化结果的字节上限，不是整个输入大小上限
 * @since 1.0.0
 */
public record JsonReadOptions(Mode mode, Selection selection, NullPolicy nullPolicy, int maxValueBytes) {

    /**
     * 根值分帧方式；NDJSON 每个非空 LF/CRLF 行须恰好一个值。
     */
    public enum Mode {
        /**
         * 唯一完整根值，可配合 Pointer 定位；空输入失败。
         */
        SINGLE_VALUE,

        /**
         * 根数组的直接元素；空数组完成，空输入失败。
         */
        ARRAY_ELEMENTS,

        /**
         * 逐行读取，空行忽略；不支持跨行值或单独 CR，末尾 LF 可省略。
         */
        NDJSON,

        /**
         * 以空白分隔的连续根值序列；空输入完成。
         */
        ROOT_VALUES
    }

    /**
     * Reactor 不允许发出 Java null；需保留 JSON null 时使用 {@link JsonValue} 作为结果类型。
     */
    public enum NullPolicy {
        /**
         * 绑定结果为 Java null 时失败。
         */
        FAIL,

        /**
         * 跳过 Java null 结果。
         */
        SKIP
    }

    /**
     * 定位单个值或目标数组的直接元素。
     * 路径缺失、目标形状错误或选中路径重复均失败；验证整篇文档须消费至结束。
     *
     * @param pointer 非空 RFC 6901 Pointer，空 Pointer 表示根
     * @param arrayElements 为 true 时逐个读取目标数组的直接元素，否则读取完整目标值
     */
    public record Selection(JsonPointer pointer, boolean arrayElements) {
        public Selection {
            Objects.requireNonNull(pointer, "pointer");
        }
    }

    public JsonReadOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(nullPolicy, "nullPolicy");
        if (maxValueBytes < 1) {
            throw new IllegalArgumentException("maxValueBytes must be positive");
        }
        if (selection != null && mode != Mode.SINGLE_VALUE) {
            throw new IllegalArgumentException("Selection requires SINGLE_VALUE mode");
        }
    }

    /**
     * 创建指定分帧方式的配置：不定位、响应式 null 策略为 FAIL、单值预算为默认值。
     */
    public static JsonReadOptions of(Mode mode) {
        return new JsonReadOptions(mode, null, NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /**
     * 从单文档定位一个完整值，使用默认 null 策略和字节预算。
     */
    public static JsonReadOptions selectValue(JsonPointer pointer) {
        return new JsonReadOptions(Mode.SINGLE_VALUE, new Selection(pointer, false),
                NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /**
     * 从单文档定位数组并逐个读取直接元素；使用默认 null 策略和逐元素字节预算。
     */
    public static JsonReadOptions selectElements(JsonPointer pointer) {
        return new JsonReadOptions(Mode.SINGLE_VALUE, new Selection(pointer, true),
                NullPolicy.FAIL, JsonOptions.DEFAULT.maxValueBytes());
    }

    /**
     * 复制配置，仅更改响应式 Java null 结果的处理策略。
     */
    public JsonReadOptions withNullPolicy(NullPolicy value) {
        return new JsonReadOptions(mode, selection, value, maxValueBytes);
    }

    /**
     * 复制配置，仅更改正整数单条记录字节预算。
     */
    public JsonReadOptions withMaxValueBytes(int value) {
        return new JsonReadOptions(mode, selection, nullPolicy, value);
    }
}
