package cn.lycode.contracts.session;

import java.time.Instant;

public record CustomMessageEntry(
    String id,
    String parentId,
    String content,
    Instant timestamp
) implements SessionEntry {}

