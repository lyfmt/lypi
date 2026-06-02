package cn.lypi.contracts.event;

public interface EventSubscription extends AutoCloseable {
    /**
     * TODO: 取消事件订阅。
     *
     * 关闭后订阅者不应再收到新事件。
     */
    @Override
    void close();
}

