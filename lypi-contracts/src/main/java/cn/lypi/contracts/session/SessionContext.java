package cn.lypi.contracts.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.util.List;

/**
 * 表示从 session 分支恢复出的模型上下文状态。
 *
 * NOTE: 不包含 system prompt、resource snapshot 或 budget；这些由 agent-core 拼装。
 */
public record SessionContext(
    List<AgentMessage> messages,
    List<String> branchEntryIds,
    List<String> appliedCompactionEntryIds,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode mode,
    PermissionMode permissionMode
) {}
