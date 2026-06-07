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

    /**
     * NOTE: 兼容旧发布端；新代码应提供状态、摘要、引用和耗时。
     */
    public ToolEndEvent(String sessionId, String toolUseId, boolean error, Instant timestamp) {
        this(
            sessionId,
            toolUseId,
            error ? ToolExecutionStatus.FAILED : ToolExecutionStatus.SUCCEEDED,
            null,
            new ToolResultSummary(
                error ? "tool failed" : "tool succeeded",
                "",
                error,
                null,
                false,
                0L,
                Map.of()
            ),
            null,
            timestamp,
            timestamp,
            0L,
            Map.of(),
            timestamp
        );
    }

    /**
     * 返回旧布尔错误语义。
     */
    public boolean error() {
        return status == ToolExecutionStatus.FAILED || status == ToolExecutionStatus.CANCELLED || status == ToolExecutionStatus.TIMED_OUT;
    }
}
