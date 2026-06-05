package cn.lypi.contracts.event;

import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import java.time.Instant;
import java.util.Map;

public record MessageDeltaEvent(
    String sessionId,
    String messageId,
    MessageRole role,
    MessageKind kind,
    String blockId,
    ContentBlockKind blockKind,
    String delta,
    boolean isFinal,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public MessageDeltaEvent {
        role = role == null ? MessageRole.ASSISTANT : role;
        kind = kind == null ? MessageKind.TEXT : kind;
        blockKind = blockKind == null ? ContentBlockKind.TEXT : blockKind;
        blockId = blockId == null || blockId.isBlank() ? messageId + ":text:0" : blockId;
        delta = delta == null ? "" : delta;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * NOTE: 旧事件没有 block 语义，兼容读取时按 assistant text block 处理。
     */
    public MessageDeltaEvent(String sessionId, String messageId, String delta, Instant timestamp) {
        this(
            sessionId,
            messageId,
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            messageId + ":text:0",
            ContentBlockKind.TEXT,
            delta,
            false,
            Map.of(),
            timestamp
        );
    }
}
