package cn.lypi.contracts.event;

import cn.lypi.contracts.hook.HookPhase;
import cn.lypi.contracts.hook.HookRunStatus;
import java.time.Instant;
import java.util.Objects;

public record HookEndEvent(
    String sessionId,
    String toolUseId,
    String parentMessageId,
    String turnId,
    String toolName,
    String hookRunId,
    String hookName,
    HookPhase phase,
    HookRunStatus status,
    String message,
    Instant startedAt,
    Instant endedAt,
    long durationMillis,
    Instant timestamp
) implements AgentEvent {
    public HookEndEvent {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        toolName = Objects.requireNonNull(toolName, "toolName");
        hookRunId = Objects.requireNonNull(hookRunId, "hookRunId");
        hookName = Objects.requireNonNull(hookName, "hookName");
        phase = Objects.requireNonNull(phase, "phase");
        status = Objects.requireNonNull(status, "status");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        endedAt = Objects.requireNonNull(endedAt, "endedAt");
        durationMillis = Math.max(0L, durationMillis);
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
