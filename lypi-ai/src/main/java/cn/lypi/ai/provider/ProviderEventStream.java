package cn.lypi.ai.provider;

public interface ProviderEventStream extends Iterable<ProviderRawEvent>, AutoCloseable {
    /**
     * 关闭 provider 原始事件流。
     *
     * NOTE: 原始事件流只允许 provider adapter 消费，不得暴露到 agent-core。
     */
    @Override
    void close();
}
