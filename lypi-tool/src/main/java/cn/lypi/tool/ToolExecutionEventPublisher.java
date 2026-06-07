package cn.lypi.tool;

import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 发布工具执行生命周期事件。
 *
 * NOTE: 事件发布失败不得影响工具执行主链路。
 */
final class ToolExecutionEventPublisher {
    private static final ProgressSink NOOP_PROGRESS = progress -> {
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
    StartedToolExecution start(
        String sessionId,
        String toolUseId,
        String parentMessageId,
        String turnId,
        String toolName,
        String displayTitle,
        String inputSummary,
        Map<String, Object> inputMetadata
    ) {
        Instant startedAt = Instant.now();
        ProgressSink progress = progressSink(sessionId, toolUseId);
        if (eventBus == null) {
            return new StartedToolExecution(startedAt, progress);
        }
        safePublish(new ToolStartEvent(
            sessionId,
            toolUseId,
            parentMessageId,
            turnId,
            toolName,
            displayTitle,
            inputSummary,
            inputMetadata,
            startedAt,
            startedAt
        ));
        return new StartedToolExecution(startedAt, progress);
    }

    /**
     * 发布工具结束事件。
     */
    void end(
        String sessionId,
        String toolUseId,
        ToolExecutionStatus status,
        Integer exitCode,
        ToolResultSummary resultSummary,
        ToolOutputRef resultRef,
        Instant startedAt,
        Instant endedAt,
        Map<String, Object> metadata
    ) {
        if (eventBus == null) {
            return;
        }
        Instant safeEndedAt = endedAt == null ? Instant.now() : endedAt;
        long durationMillis = startedAt == null ? 0L : Math.max(0L, Duration.between(startedAt, safeEndedAt).toMillis());
        safePublish(new ToolEndEvent(
            sessionId,
            toolUseId,
            status,
            exitCode,
            resultSummary,
            resultRef,
            startedAt,
            safeEndedAt,
            durationMillis,
            metadata,
            safeEndedAt
        ));
    }

    private ProgressSink progressSink(String sessionId, String toolUseId) {
        if (eventBus == null) {
            return NOOP_PROGRESS;
        }
        return progress -> {
            if (progress == null) {
                return;
            }
            safePublish(new ToolProgressEvent(sessionId, toolUseId, progress, Instant.now()));
        };
    }

    private void safePublish(AgentEvent event) {
        try {
            eventBus.publish(event);
        } catch (RuntimeException exception) {
            // NOTE: 事件总线故障不能改变工具执行结果。
        }
    }

    record StartedToolExecution(Instant startedAt, ProgressSink progressSink) {}
}
