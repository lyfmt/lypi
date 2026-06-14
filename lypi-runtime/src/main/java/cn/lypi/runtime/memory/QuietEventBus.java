package cn.lypi.runtime.memory;

import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventConsumer;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventSubscription;

/**
 * 后台隐藏 turn 使用的静默事件总线。
 */
public final class QuietEventBus implements EventBus {
    @Override
    public void publish(AgentEvent event) {
        // Intentionally drop background events.
    }

    @Override
    public EventSubscription subscribe(EventFilter filter, EventConsumer consumer) {
        return () -> {
        };
    }
}
