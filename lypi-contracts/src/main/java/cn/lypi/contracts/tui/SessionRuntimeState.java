package cn.lypi.contracts.tui;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.nio.file.Path;
import java.util.List;

public record SessionRuntimeState(
    String sessionId,
    Path cwd,
    String currentBranchLeafId,
    ModelSelection model,
    ThinkingLevel thinkingLevel,
    AgentMode agentMode,
    PermissionMode permissionMode,
    ContextBudget budget,
    List<AgentMessage> transcript,
    boolean hasInterruptibleTool,
    boolean hasActiveTurn,
    boolean hasPendingPermission,
    boolean hasPendingInput
) {
    public SessionRuntimeState {
        transcript = transcript == null ? List.of() : List.copyOf(transcript);
    }

    public SessionRuntimeState(
        String sessionId,
        Path cwd,
        String currentBranchLeafId,
        ModelSelection model,
        ThinkingLevel thinkingLevel,
        AgentMode agentMode,
        PermissionMode permissionMode,
        ContextBudget budget,
        boolean hasInterruptibleTool,
        boolean hasActiveTurn,
        boolean hasPendingPermission,
        boolean hasPendingInput
    ) {
        this(
            sessionId,
            cwd,
            currentBranchLeafId,
            model,
            thinkingLevel,
            agentMode,
            permissionMode,
            budget,
            List.of(),
            hasInterruptibleTool,
            hasActiveTurn,
            hasPendingPermission,
            hasPendingInput
        );
    }
}
