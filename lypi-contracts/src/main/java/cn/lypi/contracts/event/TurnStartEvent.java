package cn.lypi.contracts.event;

import java.time.Instant;

public record TurnStartEvent(
    String sessionId,
    String turnId,
    Instant startedAt,
    Instant timestamp
) implements AgentEvent {
    /**
     * NOTE: 兼容旧发布端；新代码应显式提供 startedAt。
     */
    public TurnStartEvent(String sessionId, String turnId, Instant timestamp) {
        this(sessionId, turnId, timestamp, timestamp);
    }
}
