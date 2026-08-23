package cn.lycode.contracts.session;

import java.time.Instant;

public record BranchSummaryEntry(
    String id,
    String parentId,
    String fromId,
    String summary,
    Instant timestamp
) implements SessionEntry {}
