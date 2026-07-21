package cn.lypi.contracts.runtime;

import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record CompactStateBackfillRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    ResourceSnapshot resourceSnapshot,
    ToolRegistrySnapshot toolRegistry,
    List<SkillMention> skillMentions
) {
    public CompactStateBackfillRequest(
        String sessionId,
        Path cwd,
        ResourceSnapshot resourceSnapshot,
        ToolRegistrySnapshot toolRegistry,
        List<SkillMention> skillMentions
    ) {
        this(sessionId, Optional.empty(), cwd, resourceSnapshot, toolRegistry, skillMentions);
    }

    public CompactStateBackfillRequest {
        sessionId = sessionId == null ? "" : sessionId;
        leafEntryId = leafEntryId == null ? Optional.empty() : leafEntryId;
        cwd = cwd == null ? Path.of(".") : cwd;
        resourceSnapshot = resourceSnapshot == null ? emptyResources() : resourceSnapshot;
        toolRegistry = toolRegistry == null ? new ToolRegistrySnapshot(List.of()) : toolRegistry;
        skillMentions = skillMentions == null ? List.of() : List.copyOf(skillMentions);
    }

    private static ResourceSnapshot emptyResources() {
        return new ResourceSnapshot(
            List.of(),
            List.of(),
            new SkillIndex(List.of(), List.of()),
            List.of(),
            List.of(),
            List.of()
        );
    }
}
