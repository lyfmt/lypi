package cn.lycode.contracts.agent;

import cn.lycode.contracts.context.AgentMessage;
import cn.lycode.contracts.context.ContextSnapshot;
import java.util.List;

public record TurnState(
    String turnId,
    String sessionId,
    ContextSnapshot context,
    List<AgentMessage> newMessages,
    int currentToolRound,
    TurnStatus status
) {}

