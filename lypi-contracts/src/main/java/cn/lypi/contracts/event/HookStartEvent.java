package cn.lypi.contracts.event;

import cn.lypi.contracts.hook.HookPhase;
import java.time.Instant;
import java.util.Objects;

public record HookStartEvent(
    String sessionId,
    String toolUseId,
    String parentMessageId,
    String turnId,
    String toolName,
    String hookRunId,
    String hookName,
    HookPhase phase,
    Instant startedAt,
    Instant timestamp
) implements AgentEvent {
    public HookStartEvent {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        toolName = Objects.requireNonNull(toolName, "toolName");
        hookRunId = Objects.requireNonNull(hookRunId, "hookRunId");
        hookName = Objects.requireNonNull(hookName, "hookName");
        phase = Objects.requireNonNull(phase, "phase");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }
}
