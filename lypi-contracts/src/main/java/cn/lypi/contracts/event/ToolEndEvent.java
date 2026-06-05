package cn.lypi.contracts.event;

import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Instant;
import java.util.Map;

public record ToolEndEvent(
    String sessionId,
    String toolUseId,
    ToolExecutionStatus status,
    Integer exitCode,
    ToolResultSummary resultSummary,
    ToolOutputRef resultRef,
    Instant startedAt,
    Instant endedAt,
    long durationMillis,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public ToolEndEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
