package cn.lypi.contracts.session;

import java.time.Instant;

public record CompactionEntry(
    String id,
    String parentId,
    String summary,
    String firstKeptEntryId,
    int tokensBefore,
    int tokensAfter,
    CompactionKind kind,
    Instant timestamp
) implements SessionEntry {}

