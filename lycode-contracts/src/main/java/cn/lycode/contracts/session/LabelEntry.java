package cn.lycode.contracts.session;

import java.time.Instant;

public record LabelEntry(
    String id,
    String parentId,
    String label,
    Instant timestamp
) implements SessionEntry {}

