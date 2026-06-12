package cn.lypi.contracts.agent;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.skill.SkillMention;
import java.util.List;
import java.util.Optional;

public record TurnRequest(
    String sessionId,
    String userInput,
    Optional<String> parentEntryId,
    AbortSignal abortSignal,
    int maxToolRounds,
    List<SkillMention> skillMentions
) {
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 16;

    public TurnRequest(
        String sessionId,
        String userInput,
        Optional<String> parentEntryId,
        AbortSignal abortSignal
    ) {
        this(sessionId, userInput, parentEntryId, abortSignal, DEFAULT_MAX_TOOL_ROUNDS, List.of());
    }

    public TurnRequest(
        String sessionId,
        String userInput,
        Optional<String> parentEntryId,
        AbortSignal abortSignal,
        int maxToolRounds
    ) {
        this(sessionId, userInput, parentEntryId, abortSignal, maxToolRounds, List.of());
    }

    public TurnRequest {
        parentEntryId = parentEntryId == null ? Optional.empty() : parentEntryId;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
        if (maxToolRounds < 0) {
            throw new IllegalArgumentException("maxToolRounds must not be negative");
        }
    }
}
