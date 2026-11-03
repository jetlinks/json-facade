package org.jetlinks.json.spi;

/**
 * 仅在单次构建中使用的适配器注册表；重复注册失败，不依赖扫描顺序覆盖。
 * 不得在模块配置回调返回后使用。
 *
 * @since 1.0.0
 * @see JsonModule#configure(JsonModuleContext)
 */
public interface JsonModuleContext {

    /**
     * 在映射器构建期为精确类型注册线程安全适配器，两个参数均不可为 null。
     * 不执行 I/O，仅影响当前构建结果；注册完成后才能并发执行映射操作。
     *
     * @param type 精确类或接口，不隐式匹配其子类或实现类
     * @param adapter 在操作线程同步调用的线程安全适配器
     * @param <T> 绑定类型
     * @throws org.jetlinks.json.JsonException 重复注册时抛出，不替换已有适配器
     * @since 1.0.0
     * @see JsonAdapter
     */
    <T> void add(Class<T> type, JsonAdapter<T> adapter);
}
