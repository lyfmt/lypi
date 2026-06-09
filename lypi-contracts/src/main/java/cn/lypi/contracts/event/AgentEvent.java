package cn.lypi.contracts.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SessionStartEvent.class, name = "session_start"),
    @JsonSubTypes.Type(value = TurnStartEvent.class, name = "turn_start"),
    @JsonSubTypes.Type(value = MessageStartEvent.class, name = "message_start"),
    @JsonSubTypes.Type(value = MessageDeltaEvent.class, name = "message_delta"),
    @JsonSubTypes.Type(value = MessageEndEvent.class, name = "message_end"),
    @JsonSubTypes.Type(value = ToolStartEvent.class, name = "tool_start"),
    @JsonSubTypes.Type(value = ToolProgressEvent.class, name = "tool_progress"),
    @JsonSubTypes.Type(value = ToolEndEvent.class, name = "tool_end"),
    @JsonSubTypes.Type(value = PermissionRequestEvent.class, name = "permission_request"),
    @JsonSubTypes.Type(value = PermissionResponseEvent.class, name = "permission_response"),
    @JsonSubTypes.Type(value = PermissionDecisionEvent.class, name = "permission_decision"),
    @JsonSubTypes.Type(value = CompactStartEvent.class, name = "compact_start"),
    @JsonSubTypes.Type(value = CompactEndEvent.class, name = "compact_end"),
    @JsonSubTypes.Type(value = RetryStartEvent.class, name = "retry_start"),
    @JsonSubTypes.Type(value = RetryEndEvent.class, name = "retry_end"),
    @JsonSubTypes.Type(value = MemoryWriteEvent.class, name = "memory_write"),
    @JsonSubTypes.Type(value = InterruptEvent.class, name = "interrupt"),
    @JsonSubTypes.Type(value = ErrorEvent.class, name = "error"),
    @JsonSubTypes.Type(value = TurnEndEvent.class, name = "turn_end")
})
public sealed interface AgentEvent permits
    SessionStartEvent,
    TurnStartEvent,
    MessageStartEvent,
    MessageDeltaEvent,
    MessageEndEvent,
    ToolStartEvent,
    ToolProgressEvent,
    ToolEndEvent,
    PermissionRequestEvent,
    PermissionResponseEvent,
    PermissionDecisionEvent,
    CompactStartEvent,
    CompactEndEvent,
    RetryStartEvent,
    RetryEndEvent,
    MemoryWriteEvent,
    InterruptEvent,
    ErrorEvent,
    TurnEndEvent {

    String sessionId();

    Instant timestamp();
}
