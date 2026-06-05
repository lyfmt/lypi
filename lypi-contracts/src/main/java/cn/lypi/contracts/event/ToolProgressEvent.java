package cn.lypi.contracts.event;

import cn.lypi.contracts.common.ToolProgress;
import java.time.Instant;

public record ToolProgressEvent(
    String sessionId,
    String toolUseId,
    ToolProgress progress,
    Instant timestamp
) implements AgentEvent {}
