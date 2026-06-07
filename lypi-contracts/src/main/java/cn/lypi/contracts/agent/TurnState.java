package cn.lypi.contracts.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.PendingPermission;
import java.util.List;
import java.util.Optional;

public record TurnState(
    String turnId,
    String sessionId,
    ContextSnapshot context,
    List<AgentMessage> newMessages,
    int currentToolRound,
    TurnStatus status,
    Optional<PendingPermission> pendingPermission
) {
    public TurnState(
        String turnId,
        String sessionId,
        ContextSnapshot context,
        List<AgentMessage> newMessages,
        int currentToolRound,
        TurnStatus status
    ) {
        this(turnId, sessionId, context, newMessages, currentToolRound, status, Optional.empty());
    }

    public TurnState {
        pendingPermission = pendingPermission == null ? Optional.empty() : pendingPermission;
    }
}
