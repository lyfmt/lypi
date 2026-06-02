package cn.lypi.contracts.event;

@FunctionalInterface
public interface EventConsumer {
    /**
     * 消费一个事件信封。
     *
     * 事件消费者用于 UI 渲染、日志记录、回放和审计派生。
     */
    void accept(EventEnvelope envelope);
}

