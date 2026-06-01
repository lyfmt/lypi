package cn.lypi.contracts.event;

public interface EventBus {
    /*
    * @status : 未完成
    * @summary : 发布一个内部事件。
    *@description : 事件总线为事件分配顺序并向 TUI、headless、日志和回放订阅者广播。
    *
    *
                              */
    void publish(AgentEvent event);

    /*
    * @status : 未完成
    * @summary : 订阅事件流。
    *@description : 订阅者只能消费标准化 AgentEvent，不直接接触 provider 原始流。
    *
    *
                              */
    EventSubscription subscribe(EventFilter filter, EventConsumer consumer);
}

