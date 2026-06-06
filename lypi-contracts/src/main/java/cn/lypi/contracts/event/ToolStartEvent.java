package cn.lypi.contracts.event;

import java.time.Instant;
import java.util.Map;

public record ToolStartEvent(
    String sessionId,
    String toolUseId,
    String parentMessageId,
    String turnId,
    String toolName,
    String displayTitle,
    String inputSummary,
    Map<String, Object> inputMetadata,
    Instant startedAt,
    Instant timestamp
) implements AgentEvent {
    public ToolStartEvent {
        inputMetadata = inputMetadata == null ? Map.of() : Map.copyOf(inputMetadata);
    }

    /**
     * NOTE: 兼容旧发布端；新代码应提供 parent message、turn 和输入摘要。
     */
    public ToolStartEvent(String sessionId, String toolUseId, String toolName, Instant timestamp) {
        this(
            sessionId,
            toolUseId,
            null,
            null,
            toolName,
            toolName,
            toolName,
            Map.of(),
            timestamp,
            timestamp
        );
    }
}
