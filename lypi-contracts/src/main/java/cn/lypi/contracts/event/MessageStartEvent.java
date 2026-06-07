package cn.lypi.contracts.event;

import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import java.time.Instant;
import java.util.Map;

public record MessageStartEvent(
    String sessionId,
    String messageId,
    MessageRole role,
    MessageKind kind,
    Map<String, Object> metadata,
    Instant timestamp
) implements AgentEvent {
    public MessageStartEvent {
        role = role == null ? MessageRole.ASSISTANT : role;
        kind = kind == null ? MessageKind.TEXT : kind;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * NOTE: 旧事件没有显式消息语义，兼容读取时按 assistant text message 处理。
     */
    public MessageStartEvent(String sessionId, String messageId, Instant timestamp) {
        this(sessionId, messageId, MessageRole.ASSISTANT, MessageKind.TEXT, Map.of(), timestamp);
    }
}
