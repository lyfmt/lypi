package cn.lypi.contracts.session;

import java.time.Instant;
import java.util.Map;

public record AgentLifecycleEntry(
    String id,
    String parentId,
    String agentId,
    String childSessionId,
    String parentSessionId,
    String lifecycle,
    Map<String, Object> metadata,
    Instant timestamp
) implements SessionEntry {
    public AgentLifecycleEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
