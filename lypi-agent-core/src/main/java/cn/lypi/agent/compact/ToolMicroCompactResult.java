package cn.lypi.agent.compact;

import cn.lypi.contracts.context.ContextSnapshot;
import java.util.List;

public record ToolMicroCompactResult(
    ContextSnapshot context,
    List<String> projectedToolUseIds
) {}
