package cn.lypi.contracts.event;

import java.time.Instant;

public record TurnEndEvent(
    String sessionId,
    String turnId,
    String status,
    Instant startedAt,
    Instant endedAt,
    long durationMillis,
    Instant timestamp
) implements AgentEvent {
    /**
     * NOTE: 兼容旧发布端；新代码应显式提供 startedAt、endedAt 和耗时。
     */
    public TurnEndEvent(String sessionId, String turnId, String status, Instant timestamp) {
        this(sessionId, turnId, status, timestamp, timestamp, 0L, timestamp);
    }
}
