package cn.lypi.contracts.session;

import java.time.Instant;
import java.util.Map;

public record SessionInfoEntry(
    String id,
    String parentId,
    Map<String, Object> metadata,
    Instant timestamp
) implements SessionEntry {}

