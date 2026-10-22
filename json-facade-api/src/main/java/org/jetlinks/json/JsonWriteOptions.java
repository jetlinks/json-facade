package org.jetlinks.json;

import java.util.Objects;

/**
 * 流式输出格式和每条记录的缓冲预算；输出块不代表记录边界。
 * 单个令牌或绑定值仍须在预算内编码完成，不能仅靠缩小输出块来处理任意大的单值。
 * 实际字节预算取本选项与映射器预算的较小值。
 *
 * @param format 根值的输出格式
 * @param chunkSize 正整数，输出块的目标字节数
 * @param maxValueBytes 正整数，单条记录或事件值的字节上限
 * @since 1.0.0
 */
public record JsonWriteOptions(Format format, int chunkSize, int maxValueBytes) {

    /**
     * 根值输出格式；NDJSON 和 ROOT_VALUES 均以 LF 分隔。
     */
    public enum Format {
        /**
         * 输入须恰好一个完整值；事件写入也使用此格式。
         */
        SINGLE_VALUE,

        /**
         * 将输入记录包装为一个根数组。
         */
        ARRAY,

        /**
         * 每条记录后追加 LF。
         */
        NDJSON,

        /**
         * 连续根值之间以 LF 分隔。
         */
        ROOT_VALUES
    }

    public JsonWriteOptions {
        Objects.requireNonNull(format, "format");
        if (chunkSize < 1 || maxValueBytes < 1) {
            throw new IllegalArgumentException("Output limits must be positive");
        }
    }

    /**
     * 使用指定格式、8 KiB 输出块和默认单条记录预算。
     */
    public static JsonWriteOptions of(Format format) {
        return new JsonWriteOptions(format, 8192, JsonOptions.DEFAULT.maxValueBytes());
    }

    /**
     * 复制配置，仅更改正整数输出块大小。
     */
    public JsonWriteOptions withChunkSize(int value) {
        return new JsonWriteOptions(format, value, maxValueBytes);
    }

    /**
     * 复制配置，仅更改正整数单条记录字节预算。
     */
    public JsonWriteOptions withMaxValueBytes(int value) {
        return new JsonWriteOptions(format, chunkSize, value);
    }
}
