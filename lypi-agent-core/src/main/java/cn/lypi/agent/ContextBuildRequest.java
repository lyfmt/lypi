package cn.lypi.agent;

import cn.lypi.contracts.skill.SkillMention;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record ContextBuildRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    boolean includeSystemPrompt,
    List<SkillMention> skillMentions
) {
    public ContextBuildRequest(
        String sessionId,
        Optional<String> leafEntryId,
        Path cwd,
        boolean includeSystemPrompt
    ) {
        this(sessionId, leafEntryId, cwd, includeSystemPrompt, List.of());
    }

    public ContextBuildRequest {
        leafEntryId = leafEntryId == null ? Optional.empty() : leafEntryId;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
    }
}
