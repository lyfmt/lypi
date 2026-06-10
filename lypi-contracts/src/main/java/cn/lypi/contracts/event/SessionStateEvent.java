package cn.lypi.contracts.event;

import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.time.Instant;

public record SessionStateEvent(
    String sessionId,
    String leafId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode agentMode,
    PermissionMode permissionMode,
    Instant timestamp
) implements AgentEvent {}
