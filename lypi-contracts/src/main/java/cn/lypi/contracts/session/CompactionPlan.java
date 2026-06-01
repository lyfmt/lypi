package cn.lypi.contracts.session;

import java.util.List;

public record CompactionPlan(
    String cutEntryId,
    String firstKeptEntryId,
    List<String> summarizedEntryIds,
    CompactionKind kind
) {}

