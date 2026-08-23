package cn.lycode.contracts.event;

import cn.lycode.contracts.common.ToolProgress;
import java.time.Instant;

public record ToolProgressEvent(
    String sessionId,
    String toolUseId,
    ToolProgress progress,
    Instant timestamp
) implements AgentEvent {}
