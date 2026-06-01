package cn.lypi.contracts.session;

import java.time.Instant;

public record CustomMessageEntry(
    String id,
    String parentId,
    String content,
    Instant timestamp
) implements SessionEntry {}

