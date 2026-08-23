package cn.lycode.agent.compact;

import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.tool.ToolRegistrySnapshot;
import java.util.List;
import java.util.Optional;

public record ToolMicroCompactRequest(
    String sessionId,
    Optional<String> leafEntryId,
    List<String> branchEntryIds,
    ContextSnapshot context,
    ToolRegistrySnapshot tools
) {
    public ToolMicroCompactRequest(
        String sessionId,
        Optional<String> leafEntryId,
        ContextSnapshot context,
        ToolRegistrySnapshot tools
    ) {
        this(sessionId, leafEntryId, List.of(), context, tools);
    }
}
