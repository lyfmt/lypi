package cn.lypi.contracts.event;

import java.time.Instant;

public record TurnEndEvent(
    String sessionId,
    String turnId,
    String status,
    Instant startedAt,
    Instant endedAt,
    long durationMillis,
    int toolRounds,
    Instant timestamp
) implements AgentEvent {
    /**
     * NOTE: 兼容旧发布端；新代码应显式提供 startedAt、endedAt、耗时和工具轮次。
     */
    public TurnEndEvent(String sessionId, String turnId, String status, Instant timestamp) {
        this(sessionId, turnId, status, timestamp, timestamp, 0L, 0, timestamp);
    }

    /**
     * NOTE: 兼容已提供耗时但尚未提供工具轮次的旧调用方。
     */
    public TurnEndEvent(
        String sessionId,
        String turnId,
        String status,
        Instant startedAt,
        Instant endedAt,
        long durationMillis,
        Instant timestamp
    ) {
        this(sessionId, turnId, status, startedAt, endedAt, durationMillis, 0, timestamp);
    }
}
