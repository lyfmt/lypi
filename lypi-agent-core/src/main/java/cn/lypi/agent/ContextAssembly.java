package cn.lypi.agent;

import cn.lypi.contracts.context.ContentReplacementRecord;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.resource.ResourceSnapshot;
import java.util.List;

public record ContextAssembly(
    ContextSnapshot snapshot,
    ResourceSnapshot resources,
    List<String> branchEntryIds,
    List<String> appliedCompactionEntryIds,
    List<ContentReplacementRecord> replacements,
    boolean budgetExceeded
) {}
