package cn.lycode.agent;

import cn.lycode.contracts.context.ContentReplacementRecord;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.resource.ResourceSnapshot;
import java.util.List;

public record ContextAssembly(
    ContextSnapshot snapshot,
    ResourceSnapshot resources,
    List<String> branchEntryIds,
    List<String> appliedCompactionEntryIds,
    List<ContentReplacementRecord> replacements,
    boolean budgetExceeded
) {}
