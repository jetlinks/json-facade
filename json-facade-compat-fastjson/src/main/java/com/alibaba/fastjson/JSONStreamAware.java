package com.alibaba.fastjson;

import java.io.IOException;

/**
 * 保留向 Appendable 输出的旧接口；不关闭调用方输出。
 * @deprecated 使用 JsonMapper.write(value, Writer)。
 * @since 1.0.0
 */
@Deprecated(since = "1.0", forRemoval = false)
public interface JSONStreamAware {
    /** @deprecated 使用 JsonMapper.write(value, Writer)。 */
    @Deprecated(since = "1.0", forRemoval = false)
    void writeJSONString(Appendable output) throws IOException;
}
