package cn.lypi.contracts.event;

import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import java.time.Instant;
import java.util.Map;

/**
 * 表示一条消息开始发布。
 *
 * NOTE: 对流式 assistant 消息，`kind` 只表示首个可消费片段的即时语义；
 * 当 metadata 中 `kindProvisional=true` 时，消费者必须以对应 MessageEndEvent.kind
 * 作为最终消息分类。
 */
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
