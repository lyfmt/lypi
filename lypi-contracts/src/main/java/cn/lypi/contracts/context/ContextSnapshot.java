package cn.lypi.contracts.context;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.util.List;

public record ContextSnapshot(
    SystemPrompt systemPrompt,
    List<AgentMessage> messages,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode mode,
    PermissionMode permissionMode,
    ContextBudget budget
) {}

