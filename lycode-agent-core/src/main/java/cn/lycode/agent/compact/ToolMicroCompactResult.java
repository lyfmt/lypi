package cn.lycode.agent.compact;

import cn.lycode.contracts.context.ContextSnapshot;
import java.util.List;

public record ToolMicroCompactResult(
    ContextSnapshot context,
    List<String> projectedToolUseIds
) {}
