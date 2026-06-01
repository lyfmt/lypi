package cn.lypi.contracts.event;

public record EventEnvelope(
    String eventId,
    String sessionId,
    long sequence,
    AgentEvent event
) {}

