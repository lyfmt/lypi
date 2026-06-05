package cn.lypi.contracts.agent;

import cn.lypi.contracts.common.AbortSignal;
import java.util.Optional;

public record TurnRequest(
    String sessionId,
    String userInput,
    Optional<String> parentEntryId,
    AbortSignal abortSignal,
    int maxToolRounds
) {
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 16;

    public TurnRequest(
        String sessionId,
        String userInput,
        Optional<String> parentEntryId,
        AbortSignal abortSignal
    ) {
        this(sessionId, userInput, parentEntryId, abortSignal, DEFAULT_MAX_TOOL_ROUNDS);
    }

    public TurnRequest {
        if (maxToolRounds < 0) {
            throw new IllegalArgumentException("maxToolRounds must not be negative");
        }
    }
}
