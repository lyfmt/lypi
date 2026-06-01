package cn.lypi.contracts.agent;

import cn.lypi.contracts.common.AbortSignal;
import java.util.Optional;

public record TurnRequest(
    String sessionId,
    String userInput,
    Optional<String> parentEntryId,
    AbortSignal abortSignal
) {}

