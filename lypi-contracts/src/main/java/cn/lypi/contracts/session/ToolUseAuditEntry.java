package cn.lypi.contracts.session;

import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Instant;
import java.util.Map;

public record ToolUseAuditEntry(
    String id,
    String parentId,
    String toolUseId,
    String parentMessageId,
    String turnId,
    String toolName,
    String displayTitle,
    String inputSummary,
    ToolExecutionStatus status,
    Integer exitCode,
    ToolResultSummary resultSummary,
    ToolOutputRef resultRef,
    Instant startedAt,
    Instant endedAt,
    long durationMillis,
    Map<String, Object> metadata,
    Instant timestamp
) implements SessionEntry {
    public ToolUseAuditEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
