package cn.lypi.tool;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import java.time.Instant;
import java.util.Objects;

/**
 * 发布工具执行生命周期事件。
 *
 * NOTE: 事件发布失败不得影响工具执行主链路。
 */
final class ToolExecutionEventPublisher {
    private static final ProgressSink NOOP_PROGRESS = message -> {
    };
    private static final ToolExecutionEventPublisher NOOP = new ToolExecutionEventPublisher(null);

    private final EventBus eventBus;

    private ToolExecutionEventPublisher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 返回不发布事件的发布器。
     */
    static ToolExecutionEventPublisher noop() {
        return NOOP;
    }

    /**
     * 返回基于事件总线的发布器。
     */
    static ToolExecutionEventPublisher eventBus(EventBus eventBus) {
        return new ToolExecutionEventPublisher(Objects.requireNonNull(eventBus, "eventBus must not be null"));
    }

    /**
     * 发布工具开始事件并返回进度 sink。
     */
    ProgressSink start(String sessionId, String toolUseId, String toolName) {
        if (eventBus == null) {
            return NOOP_PROGRESS;
        }
        safePublish(new ToolStartEvent(sessionId, toolUseId, toolName, Instant.now()));
        return message -> {
            if (message == null || message.isBlank()) {
                return;
            }
            safePublish(new ToolProgressEvent(sessionId, toolUseId, message, Instant.now()));
        };
    }

    /**
     * 发布工具结束事件。
     */
    void end(String sessionId, String toolUseId, boolean error) {
        if (eventBus == null) {
            return;
        }
        safePublish(new ToolEndEvent(sessionId, toolUseId, error, Instant.now()));
    }

    private void safePublish(AgentEvent event) {
        try {
            eventBus.publish(event);
        } catch (RuntimeException exception) {
            // NOTE: 事件总线故障不能改变工具执行结果。
        }
    }
}
