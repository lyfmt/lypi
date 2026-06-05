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
}
