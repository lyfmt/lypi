package cn.lypi.agent;

import cn.lypi.contracts.context.ContentReplacementRecord;
import cn.lypi.contracts.context.ContextSnapshot;
import java.util.List;

public record ContextAssembly(
    ContextSnapshot snapshot,
    List<String> branchEntryIds,
    List<String> appliedCompactionEntryIds,
    List<ContentReplacementRecord> replacements,
    boolean budgetExceeded
) {}
