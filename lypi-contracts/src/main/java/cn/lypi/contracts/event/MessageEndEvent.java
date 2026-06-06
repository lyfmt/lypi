package cn.lypi.contracts.event;

import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.model.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record MessageEndEvent(
    String sessionId,
    String messageId,
    MessageRole role,
    MessageKind kind,
    List<MessageBlockSnapshot> blocks,
    Optional<TokenUsage> usage,
    Optional<String> stopReason,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public MessageEndEvent {
        role = role == null ? MessageRole.ASSISTANT : role;
        kind = kind == null ? MessageKind.TEXT : kind;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        usage = usage == null ? Optional.empty() : usage;
        stopReason = stopReason == null ? Optional.empty() : stopReason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * NOTE: 旧事件没有结束快照，兼容读取时按 assistant text message 处理。
     */
    public MessageEndEvent(String sessionId, String messageId, Instant timestamp) {
        this(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            timestamp
        );
    }
}
