package cn.lycode.runtime.memory;

import cn.lycode.contracts.event.AgentEvent;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.event.EventConsumer;
import cn.lycode.contracts.event.EventFilter;
import cn.lycode.contracts.event.EventSubscription;

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
