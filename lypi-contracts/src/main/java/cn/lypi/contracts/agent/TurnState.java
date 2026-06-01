package cn.lypi.contracts.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextSnapshot;
import java.util.List;

public record TurnState(
    String turnId,
    String sessionId,
    ContextSnapshot context,
    List<AgentMessage> newMessages,
    int currentToolRound,
    TurnStatus status
) {}

