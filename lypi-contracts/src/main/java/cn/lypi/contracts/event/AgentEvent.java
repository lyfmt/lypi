package cn.lypi.contracts.event;

import java.time.Instant;

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

